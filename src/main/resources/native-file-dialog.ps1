# Shows the native Windows "Open" dialog and prints the chosen build file or folder.
# Launched by FileBrowseController in its own process so it can take the foreground from the browser.
# Usage: powershell -NoProfile -STA -ExecutionPolicy Bypass -File native-file-dialog.ps1 [initialPath]
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

# Invisible always-on-top owner window so the dialog is shown in front of everything
$owner = New-Object System.Windows.Forms.Form
$owner.TopMost = $true
$owner.ShowInTaskbar = $false
$owner.FormBorderStyle = 'None'
$owner.StartPosition = 'CenterScreen'
$owner.Size = New-Object System.Drawing.Size(1, 1)
$owner.Show()

# Windows only lets the process that received the last input take the foreground; a synthetic key press
# counts as input, which lets this process come to the front of the browser window. WScript.Shell is used
# instead of P/Invoke because Add-Type would compile C# on every launch, which is slow.
$shell = New-Object -ComObject WScript.Shell
$shell.SendKeys('{F15}')
[void]$shell.AppActivate($PID)
$owner.Activate()

$dlg = New-Object System.Windows.Forms.OpenFileDialog
$dlg.Title = 'Select a build file, or open the project folder and press Open'
$dlg.Filter = 'Build files (pom.xml, build.gradle, build.gradle.kts)|pom.xml;build.gradle;build.gradle.kts|All files (*.*)|*.*'
$dlg.CheckFileExists = $false
$dlg.CheckPathExists = $true
$dlg.ValidateNames = $false
$dlg.FileName = 'Select this folder'

if ($args.Count -gt 0 -and $args[0]) {
    $initial = $args[0]
    if (Test-Path -LiteralPath $initial -PathType Leaf) { $initial = [System.IO.Path]::GetDirectoryName($initial) }
    if (Test-Path -LiteralPath $initial -PathType Container) { $dlg.InitialDirectory = $initial }
}

$result = $dlg.ShowDialog($owner)
$owner.Close()

if ($result -eq [System.Windows.Forms.DialogResult]::OK) {
    $path = $dlg.FileName
    # Opening a folder returns "<folder>\Select this folder"; use the folder itself in that case
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -and -not (Test-Path -LiteralPath $path -PathType Container)) {
        $path = [System.IO.Path]::GetDirectoryName($path)
    }
    Write-Output $path
}
