# Scan2PC receiver (окно): принимает коды с телефона и "печатает" их в активное окно.
# Запуск: двойной щелчок по start-receiver.bat
#   или: powershell -ExecutionPolicy Bypass -File receiver.ps1 [-Port 8765] [-NoEnter]
param(
    [int]$Port = 8765,
    [switch]$NoEnter
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class Kbd {
    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Explicit)]
    struct INPUTUNION {
        [FieldOffset(0)] public KEYBDINPUT ki;
        [FieldOffset(0)] public MOUSEINPUT mi; // задаёт правильный размер union на x64
    }
    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public INPUTUNION u; }
    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint n, INPUT[] inputs, int size);
    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();

    const uint KEYUP = 0x0002, UNICODE = 0x0004;

    static INPUT Key(ushort vk, ushort scan, uint flags) {
        INPUT i = new INPUT(); i.type = 1;
        i.u.ki.wVk = vk; i.u.ki.wScan = scan; i.u.ki.dwFlags = flags;
        return i;
    }
    public static void TypeText(string s) {
        foreach (char c in s) {
            INPUT[] arr = { Key(0, c, UNICODE), Key(0, c, UNICODE | KEYUP) };
            SendInput(2, arr, Marshal.SizeOf(typeof(INPUT)));
        }
    }
    public static void Enter() {
        INPUT[] arr = { Key(0x0D, 0x1C, 0), Key(0x0D, 0x1C, KEYUP) };
        SendInput(2, arr, Marshal.SizeOf(typeof(INPUT)));
    }
}
"@

# ---------- токен и адрес ----------
# Токен: без него любой в сети мог бы печатать на этом ПК.
$tokenFile = Join-Path $PSScriptRoot 'token.txt'
if (-not (Test-Path $tokenFile)) {
    $bytes = New-Object byte[] 12
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    ([BitConverter]::ToString($bytes) -replace '-', '').ToLower() | Set-Content $tokenFile -Encoding ascii
}
$token = (Get-Content $tokenFile -Raw).Trim()

# Основной адрес: сетевой адаптер, у которого есть шлюз (то есть реальная сеть).
$ips = @()
$nics = [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
    Where-Object { $_.OperationalStatus -eq 'Up' -and $_.NetworkInterfaceType -ne 'Loopback' }
foreach ($nic in $nics) {
    $props = $nic.GetIPProperties()
    $hasGateway = $props.GatewayAddresses | Where-Object {
        $_.Address.AddressFamily -eq 'InterNetwork' -and $_.Address.ToString() -ne '0.0.0.0' }
    if ($hasGateway) {
        $props.UnicastAddresses | Where-Object { $_.Address.AddressFamily -eq 'InterNetwork' } |
            ForEach-Object { $ips += $_.Address.ToString() }
    }
}
if ($ips.Count -eq 0) {
    foreach ($nic in $nics) {
        $nic.GetIPProperties().UnicastAddresses | Where-Object {
            $_.Address.AddressFamily -eq 'InterNetwork' -and $_.Address.ToString() -notmatch '^169\.254\.' } |
            ForEach-Object { $ips += $_.Address.ToString() }
    }
}
$primaryIp = if ($ips.Count -gt 0) { $ips[0] } else { 'не найден' }

# ---------- сервер ----------
$listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Any, $Port)
try { $listener.Start() }
catch {
    [void][Windows.Forms.MessageBox]::Show(
        "Порт $Port уже занят. Скорее всего, приёмник уже запущен: закройте другое окно приёмника и откройте этот снова.",
        'Scan2PC', 'OK', 'Warning')
    exit 1
}

# ---------- окно ----------
$green = [Drawing.Color]::FromArgb(22, 128, 61)
$greenBg = [Drawing.Color]::FromArgb(232, 246, 236)
$orange = [Drawing.Color]::FromArgb(180, 83, 9)
$orangeBg = [Drawing.Color]::FromArgb(254, 243, 226)
$gray = [Drawing.Color]::FromArgb(100, 100, 100)

$form = New-Object Windows.Forms.Form
$form.Text = 'Scan2PC: приёмник'
$form.ClientSize = [Drawing.Size]::new(500, 600)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedSingle'
$form.MaximizeBox = $false
$form.BackColor = [Drawing.Color]::White
$form.Font = New-Object Drawing.Font('Segoe UI', 10)

function New-Label($text, $x, $y, $w, $h, $size = 10, $bold = $false, $color = [Drawing.Color]::Black) {
    $l = New-Object Windows.Forms.Label
    $l.Text = $text
    $l.Location = [Drawing.Point]::new($x, $y)
    $l.Size = [Drawing.Size]::new($w, $h)
    $style = if ($bold) { [Drawing.FontStyle]::Bold } else { [Drawing.FontStyle]::Regular }
    $l.Font = New-Object Drawing.Font('Segoe UI', $size, $style)
    $l.ForeColor = $color
    $form.Controls.Add($l)
    return $l
}

function New-Field($caption, $value, $y) {
    [void](New-Label $caption 24 $y 300 22 10 $true $gray)
    $tb = New-Object Windows.Forms.TextBox
    $tb.ReadOnly = $true
    $tb.Text = $value
    $tb.Font = New-Object Drawing.Font('Consolas', 14, [Drawing.FontStyle]::Bold)
    $tb.Location = [Drawing.Point]::new(24, $y + 26)
    $tb.Size = [Drawing.Size]::new(344, 34)
    $tb.BackColor = [Drawing.Color]::FromArgb(243, 246, 255)
    $tb.BorderStyle = 'FixedSingle'
    $form.Controls.Add($tb)

    $btn = New-Object Windows.Forms.Button
    $btn.Text = 'Копировать'
    $btn.Location = [Drawing.Point]::new(376, $y + 24)
    $btn.Size = [Drawing.Size]::new(100, 36)
    $btn.FlatStyle = 'System'
    $btn.Add_Click({
        [Windows.Forms.Clipboard]::SetText($tb.Text)
        $btn.Text = 'Готово'
        $t = New-Object Windows.Forms.Timer
        $t.Interval = 1200
        $t.Add_Tick({ $btn.Text = 'Копировать'; $t.Stop(); $t.Dispose() }.GetNewClosure())
        $t.Start()
    }.GetNewClosure())
    $form.Controls.Add($btn)
}

[void](New-Label 'Scan2PC: приёмник' 24 14 450 34 18 $true)
[void](New-Label 'Введите эти два значения в приложении на телефоне. Телефон и ПК должны быть в одной сети Wi-Fi.' 24 52 456 44 10 $false $gray)

New-Field '1. Адрес ПК' "${primaryIp}:$Port" 104
New-Field '2. Токен' $token 174

if ($ips.Count -gt 1) {
    [void](New-Label ('Другие адреса этого ПК: ' + (($ips | Select-Object -Skip 1) -join ', ')) 24 242 456 20 9 $false $gray)
}

$lblStatus = New-Label '' 24 270 452 48 11 $true $green
$lblStatus.BackColor = $greenBg
$lblStatus.TextAlign = 'MiddleLeft'
$lblStatus.Padding = [Windows.Forms.Padding]::new(12, 0, 12, 0)
$lblStatus.AutoEllipsis = $true

$lblFw = New-Label '' 24 328 340 40 9 $false $gray
$btnFw = New-Object Windows.Forms.Button
$btnFw.Text = 'Разрешить'
$btnFw.Location = [Drawing.Point]::new(376, 328)
$btnFw.Size = [Drawing.Size]::new(100, 36)
$btnFw.FlatStyle = 'System'
$form.Controls.Add($btnFw)

function Update-Firewall {
    $ok = [bool](Get-NetFirewallRule -DisplayName 'Scan2PC' -ErrorAction SilentlyContinue)
    if ($ok) {
        $lblFw.Text = "Брандмауэр: порт $Port открыт для телефона."
        $lblFw.ForeColor = $green
        $btnFw.Visible = $false
    } else {
        $lblFw.Text = "Если телефон не может подключиться, разрешите порт $Port в брандмауэре Windows."
        $lblFw.ForeColor = $orange
        $btnFw.Visible = $true
    }
}
$btnFw.Add_Click({
    try {
        $cmd = "New-NetFirewallRule -DisplayName 'Scan2PC' -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Any"
        Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '-NoProfile', '-Command', $cmd
    } catch { }
    Update-Firewall
})
$lblFw.Text = 'Проверяю брандмауэр...'
$btnFw.Visible = $false

$chkEnter = New-Object Windows.Forms.CheckBox
$chkEnter.Text = 'Нажимать Enter после каждого кода'
$chkEnter.Checked = -not $NoEnter
$chkEnter.Location = [Drawing.Point]::new(24, 378)
$chkEnter.Size = [Drawing.Size]::new(452, 26)
$form.Controls.Add($chkEnter)

[void](New-Label 'Принятые коды' 24 412 300 22 10 $true $gray)
$list = New-Object Windows.Forms.ListBox
$list.Location = [Drawing.Point]::new(24, 438)
$list.Size = [Drawing.Size]::new(452, 112)
$list.Font = New-Object Drawing.Font('Consolas', 9)
$list.HorizontalScrollbar = $true
$form.Controls.Add($list)

[void](New-Label 'Код вставляется туда, где стоит курсор. Не оставляйте это окно активным: кликните в нужное поле.' 24 558 456 36 9 $false $gray)

function Set-Status($text, $ok) {
    $lblStatus.Text = $text
    $lblStatus.ForeColor = if ($ok) { $green } else { $orange }
    $lblStatus.BackColor = if ($ok) { $greenBg } else { $orangeBg }
}
function Add-Log($line) {
    $list.Items.Insert(0, $line)
    if ($list.Items.Count -gt 200) { $list.Items.RemoveAt(200) }
}
Set-Status '● Ожидаю коды с телефона' $true

# ---------- обработка запросов ----------
function Send-Response($stream, $status, $body) {
    $b = [Text.Encoding]::UTF8.GetBytes($body)
    $h = [Text.Encoding]::ASCII.GetBytes("HTTP/1.1 $status`r`nContent-Length: $($b.Length)`r`nConnection: close`r`n`r`n")
    $stream.Write($h, 0, $h.Length); $stream.Write($b, 0, $b.Length)
}

function Receive-Codes($text) {
    # Пакет: один код на строку, вставляются строго по порядку.
    $active = ($form.Handle -eq [Kbd]::GetForegroundWindow())
    foreach ($code in ($text -split "`r?`n")) {
        if ($code -eq '') { continue }
        $time = Get-Date -Format 'HH:mm:ss'
        if ($active) {
            Add-Log "$time  $code   (НЕ вставлено: активно окно приёмника)"
            Set-Status '● Код пришёл, но не вставлен: кликните в нужное поле на ПК' $false
        } else {
            [Kbd]::TypeText($code)
            if ($chkEnter.Checked) {
                Start-Sleep -Milliseconds 40   # даём окну обработать введённый текст
                [Kbd]::Enter()
            }
            Add-Log "$time  $code"
            Set-Status "● Принято: $code" $true
            Start-Sleep -Milliseconds 60
        }
        [Windows.Forms.Application]::DoEvents()
    }
}

function Handle-Client($client) {
    try {
        $client.ReceiveTimeout = 3000
        $stream = $client.GetStream()

        # читаем заголовки до пустой строки (лимит 8 КБ)
        $buf = New-Object byte[] 1
        $head = New-Object Text.StringBuilder
        while ($head.Length -lt 8192 -and -not $head.ToString().EndsWith("`r`n`r`n")) {
            if ($stream.Read($buf, 0, 1) -le 0) { break }
            [void]$head.Append([char]$buf[0])
        }
        $headers = $head.ToString()

        # Проверка связи из браузера телефона: http://адрес:порт/
        if ($headers -match '^GET / ') {
            Send-Response $stream '200 OK' 'Scan2PC receiver: OK'
            Add-Log ("{0}  Телефон проверил связь" -f (Get-Date -Format 'HH:mm:ss'))
            return
        }

        # Автопоиск: телефон спрашивает, тот ли это ПК (проверка токена без вставки текста)
        if ($headers -match '^GET /ping ') {
            if ($headers -match "(?im)^X-Token:\s*$([regex]::Escape($token))\s*$") {
                Send-Response $stream '200 OK' 'ok'
            } else { Send-Response $stream '401 Unauthorized' 'bad token' }
            return
        }

        $len = 0
        if ($headers -match '(?im)^Content-Length:\s*(\d+)') { $len = [int]$Matches[1] }
        $tok = ''
        if ($headers -match '(?im)^X-Token:\s*(\S+)') { $tok = $Matches[1] }

        if ($headers -notmatch '^POST /scan ') { Send-Response $stream '404 Not Found' 'not found'; return }
        if ($tok -ne $token) {
            Send-Response $stream '401 Unauthorized' 'bad token'
            Add-Log ("{0}  Отклонено: неверный токен" -f (Get-Date -Format 'HH:mm:ss'))
            Set-Status '● Телефон прислал неверный токен: проверьте токен в приложении' $false
            return
        }
        if ($len -le 0 -or $len -gt 1048576) { Send-Response $stream '400 Bad Request' 'bad length'; return }

        $body = New-Object byte[] $len
        $got = 0
        while ($got -lt $len) {
            $n = $stream.Read($body, $got, $len - $got)
            if ($n -le 0) { break }
            $got += $n
        }
        $text = [Text.Encoding]::UTF8.GetString($body, 0, $got)
        Send-Response $stream '200 OK' 'ok'
        Receive-Codes $text
    }
    catch { Add-Log "Ошибка: $_" }
    finally { $client.Close() }
}

$timer = New-Object Windows.Forms.Timer
$timer.Interval = 60
$timer.Add_Tick({
    while ($listener.Pending()) {
        Handle-Client ($listener.AcceptTcpClient())
    }
})
$timer.Start()

$form.Add_Shown({
    $form.Activate()
    $form.Refresh()      # сначала показываем окно, потом медленная проверка брандмауэра
    Update-Firewall
})
$form.Add_FormClosed({ $timer.Stop(); $listener.Stop() })
[Windows.Forms.Application]::Run($form)
