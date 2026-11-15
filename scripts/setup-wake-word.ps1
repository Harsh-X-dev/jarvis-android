param()
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $root '.gradle/jarvis-sherpa'
New-Item -ItemType Directory -Force -Path $cache | Out-Null

function Get-Download($url, $path, $hash = '') {
    if (!(Test-Path -LiteralPath $path)) {
        $partial = "$path.partial"
        curl.exe --fail --location --silent --show-error --retry 2 --max-time 180 $url -o $partial
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $url" }
        if ($hash -and (Get-FileHash -LiteralPath $partial).Hash -ne $hash) {
            throw "Checksum mismatch: $url"
        }
        Move-Item -LiteralPath $partial -Destination $path
    }
    if ($hash -and (Get-FileHash -LiteralPath $path).Hash -ne $hash) {
        throw "Checksum mismatch: $path"
    }
}

$version = 'v2.0.1'
$model = 'sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01'
$releases = 'https://github.com/k2-fsa/sherpa-onnx/releases/download'
$raw = "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/$version"
$nativeArchive = Join-Path $cache 'native.tar.bz2'
$modelArchive = Join-Path $cache 'english-model.tar.bz2'
Get-Download "$releases/$version/sherpa-onnx-$version-android.tar.bz2" $nativeArchive '39003753ec293425ed56723ea9b972b98841a490af3311670c3060548d654330'
Get-Download "$releases/kws-models/$model.tar.bz2" $modelArchive 'F170013B4716E41B62B9BFD809687C207CEF798EF9BC6534D524E17AF9B6561A'

foreach ($archive in @($nativeArchive, $modelArchive)) {
    tar.exe -xf $archive -C $cache
    if ($LASTEXITCODE -ne 0) { throw "Could not extract $archive" }
}

$kotlin = Join-Path $cache 'kotlin'
$assets = Join-Path $cache 'assets/wake-model'
New-Item -ItemType Directory -Force -Path $kotlin, $assets | Out-Null
foreach ($name in @('KeywordSpotter', 'OnlineRecognizer', 'OnlineStream', 'FeatureConfig', 'HomophoneReplacerConfig', 'QnnConfig')) {
    Get-Download "$raw/sherpa-onnx/kotlin-api/$name.kt" (Join-Path $kotlin "$name.kt")
}

$modelDir = Join-Path $cache $model
foreach ($name in @(
    'tokens.txt',
    'encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
    'decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
    'joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx'
)) {
    Copy-Item -LiteralPath (Join-Path $modelDir $name) -Destination $assets
}
Get-Download "$raw/LICENSE" (Join-Path $assets 'sherpa-onnx-LICENSE.txt')
Write-Output "Prepared sherpa-onnx $version and the English wake-word model."
