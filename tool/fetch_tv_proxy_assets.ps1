param(
  [string]$MihomoTag = "",
  [string]$MetacubexdTag = ""
)

$ErrorActionPreference = "Stop"

function Ensure-Dir([string]$Path) {
  if (!(Test-Path $Path)) {
    New-Item -ItemType Directory -Path $Path | Out-Null
  }
}

function Get-Release([string]$Repo, [string]$Tag) {
  $headers = @{
    "User-Agent" = "LinPlayer"
    "Accept"     = "application/vnd.github+json"
  }

  if ([string]::IsNullOrWhiteSpace($Tag)) {
    return Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers $headers
  }

  return Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/tags/$Tag" -Headers $headers
}

function Download-Asset([string]$Url, [string]$OutFile) {
  Write-Host "Downloading:" $Url
  Ensure-Dir (Split-Path -Parent $OutFile)
  Invoke-WebRequest -Uri $Url -OutFile $OutFile -Headers @{ "User-Agent" = "LinPlayer" } | Out-Null
  Write-Host "Saved:" $OutFile
}

$root = Split-Path -Parent $PSScriptRoot
$assetsRoot = Join-Path $root "assets\\tv_proxy"

Ensure-Dir $assetsRoot

Write-Host "== mihomo =="
$mh = Get-Release "MetaCubeX/mihomo" $MihomoTag
Write-Host "tag:" $mh.tag_name

$arm64 = $mh.assets | Where-Object { $_.name -like "mihomo-android-arm64-*" } | Select-Object -First 1
$armv7 = $mh.assets | Where-Object { $_.name -like "mihomo-android-armv7-*" } | Select-Object -First 1

if ($arm64 -eq $null) { throw "Cannot find mihomo android arm64 asset in release $($mh.tag_name)" }
if ($armv7 -eq $null) { throw "Cannot find mihomo android armv7 asset in release $($mh.tag_name)" }

Download-Asset $arm64.browser_download_url (Join-Path $assetsRoot "mihomo\\android\\arm64-v8a\\mihomo.gz")
Download-Asset $armv7.browser_download_url (Join-Path $assetsRoot "mihomo\\android\\armeabi-v7a\\mihomo.gz")

Write-Host ""
Write-Host "== metacubexd =="
$mx = Get-Release "MetaCubeX/metacubexd" $MetacubexdTag
Write-Host "tag:" $mx.tag_name

$dist = $mx.assets | Where-Object { $_.name -eq "compressed-dist.tgz" } | Select-Object -First 1
if ($dist -eq $null) { throw "Cannot find metacubexd compressed-dist.tgz in release $($mx.tag_name)" }

Download-Asset $dist.browser_download_url (Join-Path $assetsRoot "metacubexd\\compressed-dist.tgz")

Write-Host ""
Write-Host "Done."

