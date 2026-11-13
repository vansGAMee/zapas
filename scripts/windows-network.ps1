# Run only on a trusted home network. Rules never open a public profile.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$python = Join-Path $root 'runtime\pythonw.exe'
if (!(Test-Path $python)) { throw 'Run this script from the portable Zapas folder.' }
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Start-Process powershell -Verb RunAs -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + $PSCommandPath + '"'))
    exit
}
foreach ($spec in @(@('TCP',9473),@('UDP',9474))) {
    $name = 'Zapas LAN ' + $spec[0]
    Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -DisplayName $name -Direction Inbound -Action Allow -Protocol $spec[0] -LocalPort $spec[1] -Program $python -Profile Private -RemoteAddress LocalSubnet | Out-Null
}
Write-Host 'Zapas is available on your private local network.'
