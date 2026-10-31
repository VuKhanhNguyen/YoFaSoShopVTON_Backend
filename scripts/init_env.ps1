# Tạo .env phát triển một lần, không in secret và không ghi đè cấu hình đã có.
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path $PSScriptRoot -Parent
$destination = Join-Path $backendRoot '.env'
if (Test-Path -LiteralPath $destination) {
    Write-Host '.env đã có, giữ nguyên. Hãy tự cập nhật biến cần đổi.'
    return
}
$template = Get-Content -Raw (Join-Path $backendRoot '.env.example')
$secretBytes = New-Object byte[] 48
$randomGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
$randomGenerator.GetBytes($secretBytes)
$randomGenerator.Dispose()
$template = $template.Replace('CHANGE_ME_RANDOM_MINIMUM_32_BYTES', [Convert]::ToBase64String($secretBytes))
$aiEnvPath = Join-Path (Split-Path $backendRoot -Parent) 'YoFaSoShop_AIService/.env'
if (Test-Path -LiteralPath $aiEnvPath) {
    $keyLine = Get-Content -LiteralPath $aiEnvPath | Where-Object { $_ -match '^AI_INTERNAL_API_KEY=' } | Select-Object -First 1
    if ($keyLine) {
        $aiKey = ($keyLine -split '=', 2)[1].Trim().Trim('"').Trim("'")
        if ($aiKey.Length -ge 32 -and -not $aiKey.StartsWith('CHANGE')) {
            $template = $template.Replace('CHANGE_ME_SAME_AS_AI', $aiKey)
        }
    }
}
[IO.File]::WriteAllText($destination, $template, (New-Object Text.UTF8Encoding($false)))
Write-Host 'Đã tạo .env private và JWT_SECRET ngẫu nhiên. Điền DB_PASSWORD và bootstrap admin trước khi chạy.'
