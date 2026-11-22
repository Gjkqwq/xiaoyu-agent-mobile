<#
  小鱼 · 图形界面操作工具（Windows）
  给 agent 用的「看得见、点得准」的手。
  用法：powershell -NoProfile -ExecutionPolicy Bypass -File server\gui.ps1 <命令> [参数]
#>
param(
  [Parameter(Position = 0)][string]$Cmd = 'info',
  [Parameter(Position = 1)][string]$A1 = '',
  [Parameter(Position = 2)][string]$A2 = '',
  [Parameter(Position = 3)][string]$A3 = ''
)

$ErrorActionPreference = 'Stop'
# 让输出是 UTF-8，否则 Node 那边收进来全是乱码
try {
  [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
  $OutputEncoding = [System.Text.Encoding]::UTF8
} catch {}
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
$script:HasUia = $true
try {
  Add-Type -AssemblyName UIAutomationClient
  Add-Type -AssemblyName UIAutomationTypes
} catch {
  $script:HasUia = $false
}

if (-not ('Win32Api' -as [type])) {
  Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32Api {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@
}

function Say($t) { Write-Output $t }
function Fail($t) { Say ("ERR " + $t); throw $t }

function Get-Windows {
  $out = @()
  foreach ($p in Get-Process) {
    if ($p.MainWindowHandle -eq 0) { continue }
    $r = New-Object Win32Api+RECT
    [void][Win32Api]::GetWindowRect($p.MainWindowHandle, [ref]$r)
    $out += [pscustomobject]@{
      Title  = $p.MainWindowTitle
      Proc   = $p.ProcessName
      Pid    = $p.Id
      Handle = $p.MainWindowHandle.ToInt64()
      X = $r.Left; Y = $r.Top
      W = ($r.Right - $r.Left); H = ($r.Bottom - $r.Top)
    }
  }
  return $out | Where-Object { $_.Title -and $_.W -gt 0 }
}

function Find-Window($pattern) {
  $all = @(Get-Windows)
  # 纯数字当 pid 用
  if ($pattern -match '^\d+$') {
    return $all | Where-Object { $_.Pid -eq [int]$pattern } | Select-Object -First 1
  }
  $hit = $all | Where-Object { $_.Title.IndexOf($pattern, [StringComparison]::OrdinalIgnoreCase) -ge 0 } | Select-Object -First 1
  if (-not $hit) {
    $hit = $all | Where-Object { $_.Proc.IndexOf($pattern, [StringComparison]::OrdinalIgnoreCase) -ge 0 } | Select-Object -First 1
  }
  return $hit
}

function Activate-Window($win) {
  $h = [IntPtr]$win.Handle
  if ([Win32Api]::IsIconic($h)) { [void][Win32Api]::ShowWindow($h, 9) }
  [void][Win32Api]::ShowWindow($h, 5)
  [void][Win32Api]::SetForegroundWindow($h)
  Start-Sleep -Milliseconds 400
}

function Click-Point($x, $y) {
  [void][Win32Api]::SetCursorPos([int]$x, [int]$y)
  Start-Sleep -Milliseconds 90
  [Win32Api]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero)
  Start-Sleep -Milliseconds 60
  [Win32Api]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)
}

function Collect-Tree($el, $walker, $maxDepth, $cur) {
  $out = @()
  $c = $null
  try { $c = $el.Current } catch { return @() }
  $r = $c.BoundingRectangle
  $out += [pscustomobject]@{
    Name = $c.Name
    Type = ($c.ControlType.ProgrammaticName -replace 'ControlType\.', '')
    Auto = $c.AutomationId
    Cls  = $c.ClassName
    X = [int]$r.X; Y = [int]$r.Y; W = [int]$r.Width; H = [int]$r.Height
    Depth = $cur
    El = $el
  }
  if ($cur -lt $maxDepth) {
    $child = $walker.GetFirstChild($el)
    while ($null -ne $child) {
      $out += Collect-Tree $child $walker $maxDepth ($cur + 1)
      $child = $walker.GetNextSibling($child)
    }
  }
  return $out
}

function Get-Elements($win, $depth) {
  $root = [System.Windows.Automation.AutomationElement]::RootElement
  $cond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, [int]$win.Pid)
  $tops = $root.FindAll([System.Windows.Automation.TreeScope]::Children, $cond)
  $walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
  $result = @()
  foreach ($e in $tops) {
    $result += Collect-Tree $e $walker $depth 0
  }
  return $result
}

function Invoke-Element($el) {
  try {
    $pat = $null
    if ($el.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pat)) {
      $pat.Invoke(); return 'Invoke'
    }
  } catch {}
  try {
    $pat = $null
    if ($el.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$pat)) {
      $pat.Select(); return 'Select'
    }
  } catch {}
  try {
    $pat = $null
    if ($el.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$pat)) {
      $pat.Toggle(); return 'Toggle'
    }
  } catch {}
  try {
    $pat = $null
    if ($el.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern, [ref]$pat)) {
      $pat.Expand(); return 'Expand'
    }
  } catch {}
  try {
    $r = $el.Current.BoundingRectangle
    if ($r.Width -gt 0 -and $r.Height -gt 0) {
      Click-Point ($r.X + $r.Width / 2) ($r.Y + $r.Height / 2)
      return 'ClickCenter'
    }
  } catch {}
  return 'None'
}

try { [void][Win32Api]::SetProcessDPIAware() } catch {}

function Invoke-Step { param($Cmd, $A1, $A2, $A3)
switch ($Cmd.ToLower()) {
  'info' {
    $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    Say ("屏幕: " + $b.Width + "x" + $b.Height + "  （已开 DPI 感知）")
    Say ("UI Automation: " + $(if ($script:HasUia) { '可用' } else { '不可用' }))
    Say ("可见窗口: " + (@(Get-Windows)).Count + " 个")
  }
  'windows' {
    $ws = @(Get-Windows) | Sort-Object Proc
    Say ("共 " + $ws.Count + " 个可见窗口：")
    foreach ($w in $ws) {
      Say ("  [" + $w.Proc + "] " + $w.Title + "  (" + $w.W + "x" + $w.H + " @ " + $w.X + "," + $w.Y + ")  pid=" + $w.Pid)
    }
  }
  'focus' {
    if (-not $A1) { Fail '用法: focus 窗口标题片段' }
    $w = Find-Window $A1
    if (-not $w) { Fail ("没找到窗口: " + $A1) }
    Activate-Window $w
    Say ("已切到前台: " + $w.Title)
  }
  'list' {
    if (-not $script:HasUia) { Fail 'UI Automation 不可用' }
    if (-not $A1) { Fail '用法: list 窗口标题片段 [深度]' }
    $w = Find-Window $A1
    if (-not $w) { Fail ("没找到窗口: " + $A1) }
    $depth = 4
    if ($A2) { $depth = [int]$A2 }
    Activate-Window $w
    Say ("窗口: " + $w.Title)
    $els = @(Get-Elements $w $depth)
    $shown = 0
    foreach ($e in $els) {
      if ((-not $e.Name) -and (-not $e.Auto)) { continue }
      $pad = '  ' * $e.Depth
      Say ($pad + "[" + $e.Type + "] " + $e.Name + "  auto=" + $e.Auto + "  (" + $e.X + "," + $e.Y + " " + $e.W + "x" + $e.H + ")")
      $shown++
      if ($shown -ge 150) { Say '  …（控件太多，已截断）'; break }
    }
    Say ("共 " + $shown + " 个可点控件")
  }
  'click' {
    if (-not $script:HasUia) { Fail 'UI Automation 不可用' }
    if ((-not $A1) -or (-not $A2)) { Fail '用法: click 窗口标题片段 控件名片段' }
    $w = Find-Window $A1
    if (-not $w) { Fail ("没找到窗口: " + $A1) }
    Activate-Window $w
    $els = @(Get-Elements $w 8)
    $el = $els | Where-Object { $_.Name -eq $A2 -or $_.Auto -eq $A2 } | Select-Object -First 1
    if (-not $el) {
      $el = $els | Where-Object { $_.Name -like ("*" + $A2 + "*") -or $_.Auto -like ("*" + $A2 + "*") -or $_.Cls -like ("*" + $A2 + "*") } | Select-Object -First 1
    }
    if (-not $el) { Fail ("窗口里没找到控件: " + $A2) }
    $how = Invoke-Element $el.El
    Say ("已点击 [" + $el.Type + "] " + $el.Name + "  方式=" + $how + "  位置=(" + $el.X + "," + $el.Y + ")")
  }
  'clickat' {
    if ((-not $A1) -or (-not $A2)) { Fail '用法: clickat x y' }
    Click-Point ([int]$A1) ([int]$A2)
    Say ("已点击坐标 (" + $A1 + "," + $A2 + ")")
  }
  'type' {
    if (-not $A1) { Fail '用法: type 要输入的文字 [窗口标题片段]' }
    if ($A2) {
      $w = Find-Window $A2
      if (-not $w) { Fail ("没找到窗口: " + $A2) }
      Activate-Window $w
    } else {
      Say '（提醒：没指定窗口，文字会打进当前前台窗口。建议写成 type 文字 窗口名）'
    }
    [System.Windows.Forms.SendKeys]::SendWait($A1)
    Start-Sleep -Milliseconds 200
    Say ("已输入 " + $A1.Length + " 个字符")
  }
  'keys' {
    if (-not $A1) { Fail '用法: keys ^s  或  keys {ENTER}  或  keys ^a^c [窗口标题片段]' }
    # 硬性拦截：关闭窗口/标签的按键一律拒绝（ESC 会关弹层/退出全屏，F4、Ctrl+W 直接关窗口）
    if ($A1 -match "(?i)\{ESC\}|\{F4\}|\^w") {
      Say "ERR 拒绝执行：这是关闭窗口/标签的按键。不许用按键关软件。"
      exit 3
    }
    if ($A2) {
      $w = Find-Window $A2
      if (-not $w) { Fail ("没找到窗口: " + $A2) }
      Activate-Window $w
    }
    [System.Windows.Forms.SendKeys]::SendWait($A1)
    Start-Sleep -Milliseconds 200
    Say ("已发送按键 " + $A1)
  }
  'shot' {
    $path = $A1
    if (-not $path) { $path = Join-Path $env:TEMP ("shot-" + (Get-Date -Format 'HHmmss') + ".png") }
    $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($b.X, $b.Y, 0, 0, $bmp.Size)
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
    Say ("截图已存: " + $path)
    Say ("尺寸: " + $b.Width + "x" + $b.Height)
  }
  'ensure' {
    if (-not $A1) { Fail '用法: ensure 进程名 启动目标（没在跑才启动）' }
    $procName = $A1 -replace '\.exe$', ''
    $running = @(Get-Process -Name $procName -ErrorAction SilentlyContinue)
    if ($running.Count -gt 0) {
      $w = Find-Window $procName
      $title = if ($w) { $w.Title } else { '（没有可见窗口，可能挂在托盘）' }
      Say ("已经开着了 pid=" + ($running[0].Id) + " 窗口=" + $title)
      Say 'ALREADY_RUNNING'
      exit 0
    }
    if (-not $A2) {
      Say ("没开着，而且没给启动目标。用: ensure " + $procName + " < пути\\到\\程序.exe 或 协议://>")
      exit 1
    }
    Say ("没开着，正在启动: " + $A2)
    Start-Process $A2
    for ($i = 0; $i -lt 20; $i++) {
      Start-Sleep -Milliseconds 700
      $p2 = @(Get-Process -Name $procName -ErrorAction SilentlyContinue)
      if ($p2.Count -gt 0) {
        $w2 = Find-Window $procName
        if ($w2) { Say ("启动成功 pid=" + $p2[0].Id + " 窗口=" + $w2.Title); exit 0 }
      }
    }
    Say '进程起来了但还没看到窗口（可能还在加载，或者最小化到托盘）'
    exit 0
  }
  'run' {
    if (-not $A1) { Fail '用法: run 程序/网址/协议' }
    Start-Process $A1
    Say ("已启动: " + $A1)
  }
  default {
    Say '用法: gui.ps1 <info|windows|focus|list|click|clickat|type|keys|shot|run> [参数…]'
  }
}
}

# ─────────────────────── 调度 ───────────────────────
# 单个命令，或批量：node server/gui.mjs seq "ensure|cloudmusic;;focus|cloudmusic;;shot"
if ($Cmd.ToLower() -eq 'seq') {
  $steps = @()
  try {
    $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($A1))
    $steps = $json | ConvertFrom-Json
  } catch {
    Say 'ERR 批量参数解不开'
    exit 1
  }
  $n = 0
  foreach ($s in $steps) {
    $n++
    Say ('--- ' + $n + '. ' + $s.cmd)
    try {
      Invoke-Step $s.cmd $s.a1 $s.a2 $s.a3
    } catch {
      Say ('ERR 第 ' + $n + ' 步失败：' + $_)
    }
  }
  Say ('--- 共 ' + $n + ' 步完成')
} else {
  try {
    Invoke-Step $Cmd $A1 $A2 $A3
  } catch {
    exit 1
  }
}
