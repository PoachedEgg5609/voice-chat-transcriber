# 发布到 Modrinth（API v2）
#
# 用法：
#   $env:MODRINTH_TOKEN = "<你的 token>"
#   .\publish-modrinth.ps1                       # 首次会创建项目并上传版本
#   .\publish-modrinth.ps1 -DryRun               # 只检查，不发请求
#
# token 需要 "Create versions" 权限，在 https://modrinth.com/settings/pats 创建。
# 脚本是幂等的：项目已存在时会直接上传新版本；版本号重复时会明确报错。
param(
    [string]$ProjectId = "voice-chat-transcriber",   # slug（首次发布时作为项目 slug）
    [string]$Version,                                 # 留空则读 gradle.properties 的 mod_version
    [string]$JarPath,                                 # 留空则用 build\libs 下的主 jar
    [string]$BodyPath = "MODRINTH.md",
    [string]$Loaders = "fabric",
    [string]$GameVersions = "26.1.2",
    [ValidateSet("release", "beta", "alpha")][string]$Channel = "release",
    [string]$Token = $env:MODRINTH_TOKEN,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
$api = "https://api.modrinth.com/v2"

function Invoke-Json([string]$Method, [string]$Url, $BodyObject, [string]$TokenValue) {
    $headers = @{ "User-Agent" = "voice-chat-transcriber-publisher/1.0" }
    if ($TokenValue) { $headers["Authorization"] = $TokenValue }
    $params = @{ Method = $Method; Uri = $Url; Headers = $headers; TimeoutSec = 120 }
    if ($BodyObject -ne $null) {
        $params["ContentType"] = "application/json"
        $params["Body"] = ($BodyObject | ConvertTo-Json -Depth 10 -Compress)
    }
    return Invoke-RestMethod @params
}

# --- 解析版本与产物 ---
if (-not $Version) {
    $Version = (Get-Content -LiteralPath (Join-Path $root "gradle.properties") -Encoding UTF8 |
        Select-String -Pattern '^\s*mod_version\s*=\s*(.+)$').Matches[0].Groups[1].Value.Trim()
}
if (-not $JarPath) {
    $candidates = Get-ChildItem -LiteralPath (Join-Path $root "build\libs") -Filter "*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "sources" }
    if (-not $candidates) { throw "找不到 jar，请先构建（gradle clean build），或用 -JarPath 指定" }
    $JarPath = ($candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
$jar = Get-Item -LiteralPath $JarPath
$bodyFile = Join-Path $root $BodyPath

if (-not (Test-Path -LiteralPath $bodyFile)) { throw "找不到描述文件：$bodyFile" }

Write-Host "版本     : $Version"
Write-Host "产物     : $($jar.FullName)（$([math]::Round($jar.Length/1MB,2)) MB）"
Write-Host "描述文件 : $bodyFile"
Write-Host "游戏版本 : $GameVersions   加载器: $Loaders   渠道: $Channel"
Write-Host "项目     : $ProjectId"
Write-Host ""

if ($DryRun) {
    Write-Host "DryRun：以上参数会被提交，但未发送任何请求。" -ForegroundColor Yellow
    exit 0
}

if (-not $Token) {
    throw "缺少 Modrinth token。请设置 `$env:MODRINTH_TOKEN（需 Create versions 权限），或传 -Token。"
}

# --- 项目是否存在 ---
$exists = $true
try {
    $null = Invoke-Json GET "$api/project/$ProjectId" $null $Token
    Write-Host "项目已存在，直接上传新版本。"
} catch {
    if ($_.Exception.Response.StatusCode.value__ -eq 404) { $exists = $false }
    else { throw "查询项目失败：$($_.Exception.Message)" }
}

if (-not $exists) {
    Write-Host "项目不存在，创建中…"
    $body = Get-Content -LiteralPath $bodyFile -Raw -Encoding UTF8
    $project = Invoke-Json POST "$api/project" @{
        slug          = $ProjectId
        title         = "语音转文字聊天 / Voice Chat Transcriber"
        description   = "Hold to talk, release to transcribe: speech becomes chat text. Client-only, with offline Vosk, OpenAI-compatible, iFLYTEK and local FunASR engines."
        body          = $body
        client_side   = "required"
        server_side   = "unsupported"
        project_type  = "mod"
        license       = "MIT"
        categories    = @("utility", "social")
        loaders       = @($Loaders)
        game_versions = @($GameVersions)
        initial_versions = @()
    } $Token
    Write-Host "已创建：$($project.slug)"
}

# --- 上传版本（multipart）---
Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromMinutes(30)
$client.DefaultRequestHeaders.Add("User-Agent", "voice-chat-transcriber-publisher/1.0")
$client.DefaultRequestHeaders.Add("Authorization", $Token)

$content = [System.Net.Http.MultipartFormDataContent]::new()
$fileStream = [System.IO.File]::OpenRead($jar.FullName)
$fileContent = [System.Net.Http.StreamContent]::new($fileStream)
$fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new("application/java-archive")
$content.Add($fileContent, "data", $jar.Name)

$metadata = @{
    name           = "Voice Chat Transcriber $Version"
    version_number = $Version
    changelog      = "见 GitHub 仓库的发布说明。新增 FunASR 本地服务引擎；模型组合经实测优化，比官方默认省约 45% 体积且输出一致。"
    dependencies   = @()
    game_versions  = @($GameVersions)
    version_type   = $Channel
    loaders        = @($Loaders)
    featured       = $true
    project_id     = $ProjectId
    file_parts     = @("data")
    primary_file   = "data"
} | ConvertTo-Json -Depth 6 -Compress
$content.Add([System.Net.Http.StringContent]::new($metadata), "metadata")

try {
    $response = $client.PostAsync("$api/version", $content).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw "上传失败 HTTP $([int]$response.StatusCode)：$text"
    }
    $version = $text | ConvertFrom-Json
    Write-Host ""
    Write-Host "✅ 已发布：$($version.name)" -ForegroundColor Green
    Write-Host "   https://modrinth.com/mod/$ProjectId/version/$($version.version_number)"
} finally {
    $fileStream.Dispose()
    $content.Dispose()
    $client.Dispose()
}
