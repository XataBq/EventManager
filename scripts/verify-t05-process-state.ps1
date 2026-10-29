param(
    [Parameter(Mandatory = $true)][string]$Adb,
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Java = 'C:\Program Files\Android\Android Studio1\jbr\bin\java.exe',
    [string]$ZxingJar,
    [string]$Evidence = '.gradle/t05-process'
)
$ErrorActionPreference = 'Stop'
$package = 'ru.eventpass.android.demo'
$component = "$package/ru.eventpass.app.MainActivity"
New-Item -ItemType Directory -Force -Path $Evidence | Out-Null
if (-not $ZxingJar) {
    $ZxingJar = (Get-ChildItem -LiteralPath 'C:\Users\XataB\.gradle\caches\modules-2\files-2.1\com.google.zxing\core\3.5.4' -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
}
if (-not $ZxingJar) { throw 'Pass -ZxingJar with the pinned ZXing Core 3.5.4 jar' }
function Invoke-Adb([string[]]$Arguments) {
    $result = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments[0]): $result" }
    return ($result -join "`n")
}
function Read-Window {
    $null = Invoke-Adb @('shell','uiautomator','dump','--compressed','/sdcard/t05-window.xml')
    [xml](Invoke-Adb @('exec-out','cat','/sdcard/t05-window.xml'))
}
$display = Invoke-Adb @('shell','wm','size')
if ($display -notmatch '(\d+)x(\d+)') { throw 'Cannot determine screen dimensions' }
$scrollX = [int]([int]$Matches[1] * 0.5)
$scrollStart = [int]([int]$Matches[2] * 0.8)
$scrollEnd = [int]([int]$Matches[2] * 0.3)
$safeTop = [int]([int]$Matches[2] * 0.15)
$safeBottom = [int]([int]$Matches[2] * 0.92)
$shortSwipeEnd = [int]([int]$Matches[2] * 0.6)
function Find-Node([string]$Id, [string]$Text = '', [switch]$Scroll) {
    for ($attempt = 0; $attempt -lt 16; $attempt++) {
        $window = Read-Window
        $node = $window.SelectNodes('//node') | Where-Object {
            if ($Id) { $_.'resource-id' -eq $Id -or $_.'resource-id' -like "*/$Id" } else { $_.text -eq $Text }
        } | Select-Object -First 1
        $visible = $true
        if ($node -and $Scroll -and $node.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
            $top = [int]$Matches[2]; $bottom = [int]$Matches[4]
            $visible = $top -ge $safeTop -and $bottom -le $safeBottom -and ($bottom - $top) -ge 80
        }
        if ($node -and $visible) { return $node }
        if ($Scroll) {
            if ($node -and $top -lt $safeTop) { $null = Invoke-Adb @('shell','input','swipe',"$scrollX","$shortSwipeEnd","$scrollX","$scrollStart",'350') }
            else { $null = Invoke-Adb @('shell','input','swipe',"$scrollX","$scrollStart","$scrollX","$shortSwipeEnd",'350') }
        }
        else { Start-Sleep -Milliseconds 250 }
    }
    throw "Expected UI missing: $Id $Text"
}
function Tap-Node($Node) {
    if ($Node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Invalid UI bounds' }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    $null = Invoke-Adb @('shell','input','tap',"$x","$y")
}
function Input-Field([string]$Id, [string]$Value) {
    Tap-Node (Find-Node -Id $Id -Scroll)
    $null = Invoke-Adb @('shell','input','text',$Value)
    $ime = Invoke-Adb @('shell','dumpsys','input_method')
    if ($ime -match 'mInputShown=true') { $null = Invoke-Adb @('shell','input','keyevent','KEYCODE_BACK') }
}
function Shot([string]$Name) {
    $null = Invoke-Adb @('shell','screencap','-p','/sdcard/t05-screen.png')
    $null = Invoke-Adb @('pull','/sdcard/t05-screen.png',(Join-Path $Evidence $Name))
}
function Current-Task {
    $details = Invoke-Adb @('shell','dumpsys','activity','activities')
    $line = $details -split "`n" | Where-Object { $_ -match 'ResumedActivity.*ru\.eventpass\.android\.demo/' } | Select-Object -First 1
    if ($line -notmatch '\bt(\d+)\b') { throw 'Demo task not resumed' }
    return $Matches[1]
}
# App must be installed, signed out and use the reset/open seed. No install/clear-data between steps.
$email = "process-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())@example.com"
$null = Invoke-Adb @('shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-f','0x10008000','-n',$component)
$null = Find-Node -Text 'Войти'
$null = Invoke-Adb @('shell','am','start','-W','-a','android.intent.action.VIEW','-d','eventpass-demo://demo/e/open-registration','-n',$component)
$null = Find-Node -Id 'event-open-registration'
Shot 'event.png'
Input-Field 'registration-name' 'ProcessGuest'
Input-Field 'registration-email' $email
Tap-Node (Find-Node -Id 'registration-consent' -Scroll)
Tap-Node (Find-Node -Id 'registration-submit' -Scroll)
$null = Find-Node -Id 'guest-receipt'
Tap-Node (Find-Node -Id 'open-fullscreen' -Scroll)
$null = Find-Node -Text 'Назад'
Start-Sleep -Milliseconds 700
Shot 'before.png'
$oldTask = Current-Task
$oldProcess = (Invoke-Adb @('shell','pidof',$package)).Trim()
$null = Invoke-Adb @('shell','input','keyevent','KEYCODE_HOME')
Start-Sleep -Milliseconds 900
for ($attempt = 0; $attempt -lt 12; $attempt++) {
    $null = Invoke-Adb @('shell','am','kill',$package)
    Start-Sleep -Milliseconds 500
    $remaining = & $Adb -s $Serial shell pidof $package
    if (-not $remaining) { break }
}
if ($remaining) { throw 'Background demo process was not killed' }
$null = Invoke-Adb @('shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-f','0x10200000','-n',$component)
$null = Find-Node -Text 'Назад'
$newProcess = (Invoke-Adb @('shell','pidof',$package)).Trim()
if ($oldProcess -eq $newProcess -or (Current-Task) -ne $oldTask) { throw 'Expected a new process restoring the same task' }
Start-Sleep -Milliseconds 700
Shot 'after.png'
& $Java --class-path $ZxingJar (Join-Path $PSScriptRoot 'VerifyTicketQr.java') (Join-Path $Evidence 'before.png') (Join-Path $Evidence 'after.png')
if ($LASTEXITCODE -ne 0) { throw 'Displayed QR failed independent decoding/comparison' }
# Explicitly discard only this app's task, then launch a new one. This is not the OS-death step above.
$null = Invoke-Adb @('shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-f','0x10008000','-n',$component)
$null = Find-Node -Text 'Войти'
$window = Read-Window
if ($window.SelectNodes('//node') | Where-Object { $_.'resource-id' -eq 'guest-receipt' -or $_.'content-desc' -like 'QR-билет*' }) { throw 'Fresh task resurrected a guest receipt' }
$null = Invoke-Adb @('shell','am','start','-W','-a','android.intent.action.VIEW','-d','eventpass-demo://demo/e/open-registration','-n',$component)
Input-Field 'registration-name' 'ProcessGuest'
Input-Field 'registration-email' $email
Tap-Node (Find-Node -Id 'registration-consent' -Scroll)
Tap-Node (Find-Node -Id 'registration-submit' -Scroll)
$null = Find-Node -Text 'Заявка уже есть. Воспользуйтесь письмом либо создайте аккаунт и войдите. Повторная заявка не раскрывает QR.'
Write-Output "PASS: real background process death ($oldProcess -> $newProcess), same task $oldTask, same QR; fresh task has no receipt; fake-server registration persists."
