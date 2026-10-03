# 自举式 FunASR 服务管理脚本（供 voice-chat-transcriber 模组的「FunASR 本地服务」引擎使用）
#
# 设计目标：换一台机器也能一键可用——不改系统、不污染系统 Python、不做开机自启。
#   start  幂等启动：已在跑就直接返回；否则探测 Python → 按需建隔离 venv → 按需装依赖 → 分离启动
#   stop   停止服务（先请求优雅退出，超时再强杀）
#   status 查询状态（退出码 0=在跑，1=未跑，便于脚本化判断）
#   log    打印日志末尾
#   install 只准备运行环境，不启动服务
#
# 首次 start 需要联网下载依赖（约 200 MB）与模型（约 1.13 GB，从 ModelScope）。
# 服务只绑回环地址：它没有鉴权，请勿改为 0.0.0.0。
param(
    [Parameter(Position = 0)]
    [ValidateSet("start", "stop", "status", "log", "install")]
    [string]$Action = "start",

    [int]$Port = 8321,
    [string]$Device = "cpu",
    [string]$Python = "",              # 指定解释器路径；留空则自动探测
    [string]$ModelCache = "",          # 模型缓存目录；留空则用 <根>\models
    [switch]$CpuOnly,                  # 即使有 NVIDIA GPU 也强制装 CPU 版 torch
    [switch]$UseDefaultIndex,          # 不用国内镜像，走 PyPI 官方源
    [switch]$Lazy,                     # 旧行为：先开始监听，第一次请求时才建模型（首次会卡很久）
    [string]$WheelDir = "",            # 离线安装：从本地 wheel 目录装（网络受限时先用 pip download 取好）
    [int]$ReadyTimeoutSeconds = 900,   # 首次要下载并加载约 1.13 GB 模型，默认给足 15 分钟
    [switch]$NoWait,                   # 启动后不等就绪（供模组异步调用）
    [int]$OwnerPid = $PID              # 是谁要求启动的；模组传自己的 pid，用于关闭游戏时停服务
)

$ErrorActionPreference = "Stop"
$script:Root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }

# 让所有 python 子进程用 UTF-8 做文件系统/文本 I/O。
# 中文 Windows 上 Python 3.10 及更早的 sys.getfilesystemencoding() 是 GBK，于是 pip 装
# 某些包时会崩在读取 RECORD 上：
#   UnicodeDecodeError: 'gbk' codec can't decode byte 0xaf ...
#   at pip/_internal/operations/install/legacy.py, "record_lines = f.read().splitlines()"
# 而我们的配置/模型目录常常含中文（例如 D:\Games\列车\...），所以这条是必需的。
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"
$script:Venv = Join-Path $Root "funasr-runtime"
$script:VenvPython = Join-Path $Venv "Scripts\python.exe"
$script:CachedPython = Join-Path $Root "funasr-python.txt"
# The server script is invoked through an ASCII-only path inside the runtime dir,
# with that dir as the working directory. Reason: Minecraft instances very often
# live under paths containing spaces and CJK characters (e.g.
# "...\Desktop\新建文件夹 (8)\..."), and a non-ASCII *argument* handed to
# Start-Process gets mangled -- Python then reports
# "can't find '__main__' module in 'C:\...\??????'" and exits before writing any log.
# The working directory travels through CreateProcess as UTF-16 and is safe, so all
# command-line arguments below are deliberately ASCII and relative.
$script:RuntimeDir = Join-Path $Root "funasr-runtime"
$script:RuntimeServer = Join-Path $RuntimeDir "server.py"
$script:Server = Join-Path $Root "funasr_server.py"
$script:LogDir = Join-Path $Root "logs"
$script:Log = Join-Path $LogDir "funasr-server.log"
$script:LogRelative = "..\logs\funasr-server.log"
$script:StatusRelative = "..\funasr.status"
$script:PidFile = Join-Path $Root "funasr-server.pid"
# "<pid> <port>" written by the service itself: more trustworthy than parsing a
# localised Windows process listing when it is time to stop it again.
$script:StatusFile = Join-Path $Root "funasr.status"
# Which process asked for the service. The mod writes its own pid here so that it can
# stop only a service it started, and leave a manually started one alone.
$script:OwnerFile = Join-Path $Root "funasr-server.owner"
$script:Marker = Join-Path $Root ".deps-ok"
$script:Models = if ($ModelCache) { $ModelCache } else { Join-Path $Root "models" }
# 启动追踪：脚本的**第一个动作**就写这个文件。服务日志为空时，模组据此区分
# 「脚本根本没跑（或刚开头就崩）」与「脚本跑了但 python 没起来」。
$script:TraceFile = Join-Path $Root "launcher-trace.txt"
$script:RequestedAction = $Action

# 未捕获的错误也要留痕，否则「脚本崩了」与「脚本没跑」在外部看起来一样
trap {
    $detail = ($_ | Out-String).Trim()
    try {
        Add-Content -LiteralPath $script:TraceFile -Encoding UTF8 `
            -Value ("{0}  FATAL: {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $detail)
    } catch { }
    Write-Host "启动脚本出错：$detail" -ForegroundColor Red
    exit 1
}

$Packages = @("torch", "torchaudio", "funasr", "modelscope", "fastapi", "uvicorn", "python-multipart")
# 依赖源按顺序尝试：某个镜像在对方网络里可能不可达（实测出现过 "from versions: none"，
# 而同样的源在本机是通的），所以不要只试一个。最后回落到 PyPI 官方源。
$MirrorIndexes = @(
    "https://pypi.tuna.tsinghua.edu.cn/simple",
    "https://mirrors.aliyun.com/pypi/simple",
    "https://mirrors.ustc.edu.cn/pypi/simple",
    "https://mirrors.cloud.tencent.com/pypi/simple",
    "https://pypi.org/simple"
)
# 版本区间来自依赖自身的 Requires-Python 声明与实测：torch / modelscope / fastapi /
# uvicorn 都是 >= 3.10，funasr 只要 3.7（不是瓶颈）。上限不是硬限制——只是如果解释器
# 比实测版本更新，某些依赖可能还没有对应的预编译包，装不上时会被如实报出来。
$MinimumPython = "3.10"
$TestedUpTo = "3.14"

function Write-Step($text) { Write-Host "  $text" }
function Write-Head($text) { Write-Host $text -ForegroundColor Cyan }

function Write-Trace($text) {
    try {
        Add-Content -LiteralPath $script:TraceFile -Encoding UTF8 `
            -Value ("{0}  {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $text)
    } catch { }
}

# 第一件事就留痕：能执行到这里说明 PowerShell 跑起来了、路径也解析成功
Write-Trace "action=$script:RequestedAction port=$Port powershell=$($PSVersionTable.PSVersion) pid=$PID"
Write-Trace "root=$Root"

function Test-ServiceUp([int]$p) {
    try {
        $response = Invoke-RestMethod -Uri "http://127.0.0.1:$p/health" -TimeoutSec 4
        return $null -ne $response.status
    } catch { return $false }
}

function Get-ServicePid([int]$p) {
    $connection = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($connection) { return $connection.OwningProcess }
    return $null
}

# 按行拆分。不能用 -split "\R"：.NET Framework（Windows PowerShell 5.1）的正则不支持
# \R，会抛 "Unrecognized escape sequence \R" 并让脚本在任何候选被判断前就退出。
function Split-Lines {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return @() }
    return ($Text -split "\r?\n")
}

function Test-Deps([string]$exe) {
    # 实测导入，才能同时排除 Microsoft Store 占位 stub 和缺失依赖的解释器
    $probe = "import importlib.util as u,sys;sys.exit(0 if all(u.find_spec(m) for m in ['torch','funasr','fastapi','uvicorn','multipart']) else 1)"
    $out = Invoke-Python -Exe $exe -Arguments @("-c", $probe)
    return ($out.ExitCode -eq 0)
}

<#
    运行一个 python 候选并返回退出码与输出。

    用 .NET 直接启动、显式重定向，而不是 `& $exe ... 2>$null`：在
    `$ErrorActionPreference = "Stop"` 下，后者遇到原生命令写 stderr 可能变成
    **终止性错误**，让整个脚本在任何候选被判断之前就退出——那正好表现为
    「日志与 models 都是空的」。同时避免 `$LASTEXITCODE` 在 .cmd/.bat 垫片
    （如 Microsoft Store 的 python.cmd）上不可靠。

    注意：不能用 `ProcessStartInfo.ArgumentList`——那是 .NET Core 才有的属性，
    Windows PowerShell 5.1 基于 .NET Framework，取到 $null 会抛
    「You cannot call a method on a null-valued expression」。这里手工拼接并按
    Windows 规则转义。
#>
function ConvertTo-ArgumentString {
    param([string[]]$Arguments)
    $parts = foreach ($a in $Arguments) {
        $value = if ($null -eq $a) { "" } else { [string]$a }
        if ($value -match '[\s"]') {
            # 先转义结尾反斜杠与内部引号，再整体加引号（Windows 命令行规则）
            $escaped = $value -replace '(\\*)"', '$1$1\"'
            $escaped = $escaped -replace '(\\+)$', '$1$1'
            '"' + $escaped + '"'
        } else {
            $value
        }
    }
    return ($parts -join ' ')
}

function Invoke-Python {
    param([string]$Exe, [string[]]$Arguments, [int]$TimeoutSeconds = 40)
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $Exe
    $info.Arguments = ConvertTo-ArgumentString $Arguments
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.CreateNoWindow = $true
    $process = $null
    try {
        $process = [System.Diagnostics.Process]::Start($info)
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try { $process.Kill() } catch { }
            return @{ ExitCode = -1; Output = ""; Error = "timeout after ${TimeoutSeconds}s" }
        }
        return @{ ExitCode = $process.ExitCode; Output = $stdout.Result.Trim(); Error = $stderr.Result.Trim() }
    } catch {
        return @{ ExitCode = -1; Output = ""; Error = ($_ | Out-String).Trim() }
    } finally {
        if ($process) { $process.Dispose() }
    }
}

function Get-HasNvidia {
    if ($CpuOnly) { return $false }
    return $null -ne (Get-Command nvidia-smi -ErrorAction SilentlyContinue)
}

function Resolve-SystemPython {
    if ($Python) {
        if (-not (Test-Path -LiteralPath $Python)) { throw "指定的 Python 不存在：$Python" }
        Write-Trace "python=explicit path $Python"
        return $Python
    }
    if (Test-Path -LiteralPath $script:CachedPython) {
        $cached = (Get-Content -LiteralPath $script:CachedPython -Raw).Trim()
        if ($cached -and (Test-Path -LiteralPath $cached)) {
            Write-Trace "python=cached $cached"
            return $cached
        }
        Write-Trace "cached python missing: $cached"
    }

    # 候选来源要广：很多人安装 Python 时不勾选 "Add python.exe to PATH"，
    # 这时 py 启动器或 %LOCALAPPDATA%\Programs\Python 是唯一能找到它的地方。
    $candidates = New-Object System.Collections.Generic.List[string]
    $py = Get-Command py -ErrorAction SilentlyContinue
    if ($py) {
        $listing = Invoke-Python -Exe $py.Source -Arguments @("-0p") -TimeoutSeconds 15
        Write-Trace ("py -0p exit={0} out={1}" -f $listing.ExitCode, ($listing.Output -replace "\s+", " "))
        foreach ($line in (Split-Lines $listing.Output)) {
            if ($line -match '([A-Za-z]:\\[^\s].*?python\.exe)') { $candidates.Add($matches[1].Trim()) }
        }
    } else {
        Write-Trace "py launcher not found on PATH"
    }
    foreach ($name in @("python3", "python")) {
        $found = Get-Command $name -ErrorAction SilentlyContinue
        if ($found) { $candidates.Add($found.Source) }
    }
    foreach ($pattern in @(
            (Join-Path $env:LOCALAPPDATA "Programs\Python\Python3*\python.exe"),
            "$env:ProgramFiles\Python3*\python.exe",
            "${env:ProgramFiles(x86)}\Python3*\python.exe",
            "$env:LOCALAPPDATA\Microsoft\WindowsApps\PythonSoftwareFoundation.Python.*\python.exe")) {
        try {
            Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue |
                Sort-Object FullName -Descending | ForEach-Object { $candidates.Add($_.FullName) }
        } catch { }
    }

    $rejected = New-Object System.Collections.Generic.List[string]
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        $why = $null
        if ($candidate -like "*\WindowsApps\*") {
            # 只有当它是 Microsoft Store 的占位垫片时才跳过；真正从商店安装的
            # Python 也会位于 WindowsApps 下，所以再往下用一次探针确认。
            $why = "WindowsApps stub path"
        } elseif (-not (Test-Path -LiteralPath $candidate)) {
            $why = "path does not exist"
        }
        if ($why) { $rejected.Add("$candidate（$why）"); continue }

        $probe = Invoke-Python -Exe $candidate -Arguments @("-c", "import sys;print('%d.%d.%d' % sys.version_info[:3])")
        if ($probe.ExitCode -ne 0 -or $probe.Output -notmatch '^(\d+)\.(\d+)\.(\d+)') {
            $detail = if ($probe.Error) { (Split-Lines $probe.Error)[0] } else { "exit=$($probe.ExitCode)" }
            $rejected.Add("$candidate（$detail）")
            continue
        }
        $version = [version]"$($Matches[1]).$($Matches[2]).$($Matches[3])"
        # 硬性下限来自依赖自身的声明：torch / modelscope / fastapi / uvicorn 都是
        # Requires-Python >= 3.10（funasr 只要 3.7，不是瓶颈）。
        if ($version -lt [version]$MinimumPython) {
            $rejected.Add("$candidate（Python $version 低于最低要求 $MinimumPython，torch 等依赖不支持）")
            continue
        }
        # 版本过新同样是问题：还没有对应的预编译包时，pip 会尝试从源码构建并失败。
        # 这里不预先判定（取决于各项目的发布节奏），而是记录版本，让 pip 阶段如实报错。
        if ($version -gt [version]$TestedUpTo) {
            Write-Trace "python $version 高于实测过的 $TestedUpTo，依赖可能没有对应预编译包"
            Write-Step "注意：Python $version 高于本次实测过的 $TestedUpTo，若依赖安装失败请改用 $MinimumPython–$TestedUpTo"
        }
        Write-Trace "python=$candidate version=$version"
        Write-Step "找到 Python $version：$candidate"
        return $candidate
    }

    foreach ($entry in $rejected) { Write-Trace "rejected: $entry" }
    throw ("找不到可用的 Python（需要 $MinimumPython 以上；实测通过的是 $MinimumPython–$TestedUpTo）。" +
           "已尝试 $($rejected.Count) 个候选。请安装 Python 并勾选 Add python.exe to PATH，" +
           "或用 -Python <路径> 指定。")
}

# 运行 pip 并把结果写进追踪文件。用 .NET 捕获输出而不是 `& pip ...`：后者在
# $ErrorActionPreference = "Stop" 下，pip 往 stderr 写任何东西都可能变成终止性错误，
# 于是「装不上」会表现为脚本静默退出，看不出原因。
function Invoke-Pip {
    param([string]$PythonExe, [string[]]$PipArguments, [int]$TimeoutSeconds = 3600)
    $all = @("-m", "pip") + $PipArguments
    Write-Trace ("pip " + ($PipArguments -join ' '))
    $result = Invoke-Python -Exe $PythonExe -Arguments $all -TimeoutSeconds $TimeoutSeconds
    if ($result.ExitCode -ne 0) {
        $tail = (Split-Lines ($result.Output + "`n" + $result.Error)) |
            Where-Object { $_ -match '\S' } | Select-Object -Last 6
        foreach ($line in $tail) { Write-Trace "pip-output: $line" }
    }
    return $result.ExitCode
}

# 依次尝试多个源，任一成功即返回 $true。
# 只试一个源是不可靠的：对方网络里某个镜像可能完全不可达（表现为
# "ERROR: Could not find a version that satisfies the requirement funasr
#  (from versions: none)"），而同样的源在别处是通的。
function Install-WithMirrors {
    param([string]$PythonExe, [string[]]$Packages)
    $common = @("install", "--no-input", "--disable-pip-version-check",
                "--no-warn-script-location", "--retries", "5", "--timeout", "60")
    # 离线通道：网络受限的机器可以先用 pip download 取好 wheel，再用 -WheelDir 指定。
    if ($WheelDir) {
        if (-not (Test-Path -LiteralPath $WheelDir)) { throw "指定的 wheel 目录不存在：$WheelDir" }
        $offlineArgs = $common + @("--no-index", "--find-links", $WheelDir) + $Packages
        if ((Invoke-Pip -PythonExe $PythonExe -PipArguments $offlineArgs) -eq 0) {
            Write-Trace "installed from local wheel dir: $WheelDir"
            return $true
        }
        Write-Trace "local wheel dir did not satisfy everything, falling back to mirrors"
    }
    $indexes = if ($UseDefaultIndex) { @("https://pypi.org/simple") } else { $MirrorIndexes }
    foreach ($index in $indexes) {
        $pipArgs = $common + @("-i", $index) + $Packages
        if ((Invoke-Pip -PythonExe $PythonExe -PipArguments $pipArgs) -eq 0) {
            Write-Trace "mirror ok: $index"
            return $true
        }
        Write-Step "源不可用，换下一个：$index"
    }
    return $false
}

function Install-Packages([string]$pipPython) {
    Write-Step "安装依赖（约 200 MB，首次可能需要几分钟）…"

    # 先把 pip 自身升级：老版本 pip 在中文 locale + 中文路径下会崩在读取 RECORD 上
    # （UnicodeDecodeError: 'gbk' codec ...），升级 pip 是官方修法之一，成本也很低。
    foreach ($index in ($(if ($UseDefaultIndex) { @("https://pypi.org/simple") } else { $MirrorIndexes }))) {
        if ((Invoke-Pip -PythonExe $pipPython -PipArguments @(
                "install", "--no-input", "--disable-pip-version-check", "-i", $index, "--upgrade", "pip")) -eq 0) {
            Write-Trace "pip upgraded via $index"
            break
        }
    }

    $torchInstalled = $false
    if (Get-HasNvidia) {
        Write-Step "检测到 NVIDIA GPU，安装 CUDA 版 torch（改用 -CpuOnly 可强制 CPU 版）"
        $cuda = @("install", "--no-input", "--disable-pip-version-check", "--no-warn-script-location",
                  "--retries", "5", "--timeout", "60",
                  "torch", "torchaudio", "--index-url", "https://download.pytorch.org/whl/cu126")
        if ((Invoke-Pip -PythonExe $pipPython -PipArguments $cuda) -eq 0) {
            $torchInstalled = $true
        } else {
            Write-Step "CUDA 版 torch 安装失败，改用 CPU 版"
        }
    }
    if (-not $torchInstalled) {
        if (-not (Install-WithMirrors -PythonExe $pipPython -Packages @("torch", "torchaudio"))) {
            throw ("torch/torchaudio 安装失败：所有依赖源都不可用，或该 Python 版本没有对应的预编译包。" +
                   "详见 launcher-trace.txt（可指定 -UseDefaultIndex，或先手动装好 torch 后重试）")
        }
    }
    if (-not (Install-WithMirrors -PythonExe $pipPython `
            -Packages @("funasr", "modelscope", "fastapi", "uvicorn", "python-multipart"))) {
        throw ("funasr 及其依赖安装失败：所有依赖源都不可用（在对方网络里镜像可能被拦截）。" +
               "详见 launcher-trace.txt 的 pip-output 行")
    }
}

function Initialize-Runtime {
    # JDK 风格的固定运行目录：脚本被复制到这里，命令行只用 ASCII 相对路径引用它
    New-Item -ItemType Directory -Force -Path $script:RuntimeDir, $script:LogDir, $script:Models | Out-Null

    # 优先复用系统里已经装好的环境：跨机器时若系统 Python 已有依赖，可直接用，省下 200 MB 下载
    $systemPython = Resolve-SystemPython
    Set-Content -LiteralPath $script:CachedPython -Value $systemPython -Encoding UTF8
    Copy-Item -LiteralPath $script:Server -Destination $script:RuntimeServer -Force
    if (Test-Deps $systemPython) {
        Write-Step "系统 Python 已具备全部依赖，直接复用（不建 venv）"
        return $systemPython
    }

    if ((Test-Path -LiteralPath $script:VenvPython) -and (Test-Deps $script:VenvPython)) {
        Write-Step "复用已有隔离环境：$script:Venv"
        return $script:VenvPython
    }

    if (-not (Test-Path -LiteralPath $script:VenvPython)) {
        Write-Step "创建隔离环境（不污染系统 Python）：$script:Venv"
        $venv = Invoke-Python -Exe $systemPython -Arguments @("-m", "venv", $script:Venv) -TimeoutSeconds 300
        if ($venv.ExitCode -ne 0) {
            foreach ($line in (Split-Lines ($venv.Output + "`n" + $venv.Error)) | Where-Object { $_ -match '\S' } | Select-Object -Last 5) {
                Write-Trace "venv-output: $line"
            }
            throw "创建 venv 失败（$systemPython）。可改用 -Python <已装好依赖的路径> 绕过。"
        }
        Write-Trace "venv created at $script:Venv"
    }
    Install-Packages $script:VenvPython
    if (-not (Test-Deps $script:VenvPython)) {
        throw "依赖装完了但仍无法导入（详见 launcher-trace.txt 的 pip-output 行）"
    }
    Set-Content -LiteralPath $script:Marker -Value (Get-Date -Format s) -Encoding UTF8
    return $script:VenvPython
}

function Start-Service([string]$exe) {
    New-Item -ItemType Directory -Force -Path $LogDir, $Models | Out-Null
    $env:MODELSCOPE_CACHE = $Models
    $env:PYTHONUNBUFFERED = "1"

    # 必须用 python.exe 而不是 pythonw.exe：实测 pythonw（无控制台子系统）启动后服务会在
    # uvicorn 起来之前静默退出，且没有任何报错——因为它的 stdout/stderr 句柄无效。
    # 用 python.exe 配 -WindowStyle Hidden 同样不弹窗口，而且句柄正常。
    #
    # 所有参数都必须是 ASCII 相对路径：见 $script:RuntimeServer 上方的说明。工作目录走
    # CreateProcess 的 Unicode 字段，中文路径没问题；命令行参数一旦含中文就会损坏。
    $serverArgs = @(".\server.py", "--host", "127.0.0.1", "--port", "$Port", "--device", $Device,
                    "--log-file", $script:LogRelative, "--status-file", $script:StatusRelative)
    # 默认由服务端「先建好模型再开始监听」，这样 /health 的 ready 才是真的——模组据此
    # 显示「已就绪」，不会出现「显示就绪、按下去却卡两分钟」的情况。要旧的懒加载用 -Lazy。
    if ($Lazy) { $serverArgs += "--lazy" }

    # 记录是谁要求启动的，模组据此判断「这个服务是不是我起的」，从而只停自己起的那份
    Set-Content -LiteralPath $script:OwnerFile -Value "$OwnerPid" -Encoding UTF8
    Remove-Item -LiteralPath $script:StatusFile -Force -ErrorAction SilentlyContinue

    # 日志由服务端的 --log-file 直接写盘：分离进程继承的 stdout/stderr 句柄会长时间不刷新
    # （实测重定向出来的日志文件一直是 0 字节），失败时就无从排查。
    $process = Start-Process -FilePath $exe -ArgumentList $serverArgs -WorkingDirectory $script:RuntimeDir `
        -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $script:PidFile -Value $process.Id -Encoding UTF8
    Write-Step "已分离启动（pid=$($process.Id)），日志：$script:Log"
    return $process
}

function Get-RecordedPid {
    # 状态文件由服务自己写，最可靠；退回到启动脚本记录的 pid
    if (Test-Path -LiteralPath $script:StatusFile) {
        $first = (Get-Content -LiteralPath $script:StatusFile -Raw -ErrorAction SilentlyContinue)
        if ($first -and $first.Trim() -match '^(\d+)') { return [int]$Matches[1] }
    }
    if (Test-Path -LiteralPath $script:PidFile) {
        $recorded = (Get-Content -LiteralPath $script:PidFile -Raw -ErrorAction SilentlyContinue)
        if ($recorded -and $recorded.Trim() -match '^(\d+)$') { return [int]$recorded.Trim() }
    }
    return $null
}

function Stop-FunasrService([int]$p) {
    # 优先让服务自己优雅退出（它能落盘「service stopped」），超时再强杀
    try {
        Invoke-RestMethod -Uri "http://127.0.0.1:$p/shutdown" -Method Post -TimeoutSec 5 | Out-Null
    } catch { }
    $deadline = (Get-Date).AddSeconds(8)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-ServicePid $p)) { return $true }
        Start-Sleep -Milliseconds 400
    }
    foreach ($target in @((Get-ServicePid $p), (Get-RecordedPid))) {
        if ($target) { Stop-Process -Id $target -Force -ErrorAction SilentlyContinue }
    }
    Start-Sleep -Milliseconds 600
    return $null -eq (Get-ServicePid $p)
}

function Wait-Ready([int]$timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    Write-Step "等待服务就绪（首次需下载并加载约 1.13 GB 模型，可能数分钟）…"    while ((Get-Date) -lt $deadline) {
        if (Test-ServiceUp $Port) { return $true }
        if (-not (Get-ServicePid $Port) -and -not (Get-Process -Id (Get-Content $script:PidFile -ErrorAction SilentlyContinue) -ErrorAction SilentlyContinue)) {
            return $false
        }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Show-LogTail([int]$lines = 20) {
    if (Test-Path -LiteralPath $script:Log) {
        Write-Host "--- $script:Log (末尾 $lines 行) ---" -ForegroundColor Yellow
        Get-Content -LiteralPath $script:Log -Tail $lines -Encoding UTF8
    } else {
        Write-Host "（还没有日志文件：$script:Log）" -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------------------

switch ($Action) {
    "install" {
        Write-Head "准备 FunASR 运行环境（不启动服务）"
        $env:MODELSCOPE_CACHE = $Models
        $exe = Initialize-Runtime
        Write-Host "环境就绪：$exe" -ForegroundColor Green
        exit 0
    }

    "status" {
        if (Test-ServiceUp $Port) {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 4
            Write-Host "status: RUNNING port=$Port pid=$(Get-ServicePid $Port)"
            Write-Host "  device=$($health.device) models_loaded=$($health.models_loaded -join ',')"
            exit 0
        }
        $listener = Get-ServicePid $Port
        if ($listener) { Write-Host "status: STARTING port=$Port pid=$listener（端口已监听，健康检查未通过）"; exit 1 }
        Write-Host "status: NOT RUNNING"
        exit 1
    }

    "stop" {
        $listener = Get-ServicePid $Port
        $recorded = Get-RecordedPid
        if (-not $listener -and -not $recorded) {
            Write-Host "stop: not running (nothing to do)"
            Remove-Item -LiteralPath $script:PidFile, $script:StatusFile, $script:OwnerFile -Force -ErrorAction SilentlyContinue
            exit 0
        }
        $wasRunning = $null -ne $listener
        $stopped = Stop-FunasrService $Port
        Remove-Item -LiteralPath $script:PidFile, $script:StatusFile, $script:OwnerFile -Force -ErrorAction SilentlyContinue
        if ($stopped) {
            Write-Host "stop: done$(if ($wasRunning) { " (pid=$listener)" })"
            exit 0
        }
        Write-Host "stop: 未能停止（端口 $Port 仍被占用，可能有其他进程在用）" -ForegroundColor Yellow
        exit 1
    }

    "log" {
        if (-not (Test-Path -LiteralPath $script:Log)) { Write-Host "还没有日志：$script:Log"; exit 0 }
        Show-LogTail 60
        exit 0
    }

    "start" {
        if (Test-ServiceUp $Port) {
            Write-Host "start: already running port=$Port pid=$(Get-ServicePid $Port)" -ForegroundColor Green
            exit 0
        }
        if (Get-ServicePid $Port) {
            Write-Host "start: 端口 $Port 已被占用（可能是正在启动的服务，或别的程序）" -ForegroundColor Yellow
            exit 1
        }
        if (-not (Test-Path -LiteralPath $script:Server)) { throw "找不到服务脚本：$script:Server" }

        Write-Head "启动 FunASR 本地服务"
        Write-Step "根目录：$Root"
        Write-Step "模型缓存：$script:Models"
        Write-Trace "python-candidates starting"
        $exe = Initialize-Runtime
        Write-Trace "python=$exe"
        $started = Start-Service $exe
        Write-Trace "python-pid=$($started.Id) log=$script:Log"

        if ($NoWait) { Write-Host "start: 已启动（未等待就绪）"; exit 0 }
        if (Wait-Ready $ReadyTimeoutSeconds) {
            Write-Host "start: ready on http://127.0.0.1:$Port/v1/audio/transcriptions" -ForegroundColor Green
            exit 0
        }
        Write-Host "start: 服务未在 $ReadyTimeoutSeconds 秒内就绪" -ForegroundColor Red
        Show-LogTail 25
        exit 1
    }
}
