param(
    [Parameter(Mandatory = $true)][string]$Adb,
    [Parameter(Mandatory = $true)][string]$Serial
)
$ErrorActionPreference = 'Stop'
$package = 'ru.eventpass.android.demo'
$component = "$package/ru.eventpass.app.MainActivity"

function Invoke-Adb([string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' '): $output" }
    return ($output -join "`n")
}
function Read-Window {
    $null = Invoke-Adb @('shell', 'uiautomator', 'dump', '/sdcard/t01-window.xml')
    [xml](Invoke-Adb @('exec-out', 'cat', '/sdcard/t01-window.xml'))
}
function Find-Text([string]$Text) {
    $window = Read-Window
    $node = $window.SelectNodes('//node') | Where-Object { $_.text -eq $Text } | Select-Object -First 1
    if (-not $node) { throw "Expected text missing: $Text" }
    return $node
}
function Tap-Text([string]$Text) {
    $node = Find-Text $Text
    if ($node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Invalid bounds' }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    $null = Invoke-Adb @('shell', 'input', 'tap', "$x", "$y")
}

# Only this project's demo package is reset. No app data or emulator snapshot is deleted.
$null = Invoke-Adb @('shell', 'am', 'force-stop', $package)
$null = Invoke-Adb @('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d',
    'eventpass-demo://demo/demo', '-n', $component)
$null = Find-Text 'Счётчик: 0'
Tap-Text 'Увеличить'
$null = Find-Text 'Счётчик: 1'
Tap-Text 'Профиль'
$oldProcess = (Invoke-Adb @('shell', 'pidof', $package)).Trim()
$null = Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_HOME')
Start-Sleep -Milliseconds 800
$remaining = $oldProcess
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    $null = Invoke-Adb @('shell', 'am', 'kill', $package)
    Start-Sleep -Milliseconds 500
    $remaining = & $Adb -s $Serial shell pidof $package
    if (-not $remaining) { break }
}
if ($remaining) { throw "Process was not killed: $remaining" }
$null = Invoke-Adb @('shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c',
    'android.intent.category.LAUNCHER', '-f', '0x10200000', '-n', $component)
$newProcess = (Invoke-Adb @('shell', 'pidof', $package)).Trim()
if ($oldProcess -eq $newProcess) { throw 'Expected a new process' }
$null = Find-Text 'Это демонстрационная оболочка без входа и аккаунта.'
Tap-Text 'Билеты'
$null = Find-Text 'Счётчик: 1'
Tap-Text 'Закрыть экран'
Tap-Text 'Открыть демо-экран'
$null = Find-Text 'Счётчик: 0'
# A fresh task after explicit finish must not resurrect entry state.
$null = Invoke-Adb @('shell', 'am', 'force-stop', $package)
$null = Invoke-Adb @('shell', 'am', 'start', '-W', '-n', $component)
$null = Find-Text 'Мои билеты'
Write-Output "PASS: cold link, real process death ($oldProcess -> $newProcess), tab/entry restoration, pop, fresh launch."
