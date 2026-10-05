param([string]$Id)
$ErrorActionPreference = 'Stop'
[xml]$x = Get-Content 'app\build\reports\lint-results-directDebug.xml'
$issues = $x.issues.issue | Where-Object { $Id -eq '' -or $_.id -match $Id }
$issues | Group-Object id | Sort-Object Count -Descending | ForEach-Object {
    "===== $($_.Name) ($($_.Count)) ====="
    $_.Group | ForEach-Object {
        $file = Split-Path $_.location.file -Leaf
        "  $($_.message)"
        "    -> ${file}:$($_.location.line)"
    }
}
