# keepalive.ps1

Pings the free-tier Render services every 10 minutes so they don't spin down (Render
sleeps a free service after ~15 min without requests).

## Run it

Foreground (see the output, Ctrl+C to stop):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\keepalive.ps1
```

Hidden in the background for this session:

```powershell
Start-Process powershell -WindowStyle Hidden -ArgumentList '-ExecutionPolicy Bypass -File "scripts\keepalive.ps1"'
```

Stop it: Task Manager -> the `powershell.exe` running keepalive, or
`Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object CommandLine -like '*keepalive*' | ForEach-Object { Stop-Process -Id $_.ProcessId }`

## Start automatically at every login

```powershell
$action  = New-ScheduledTaskAction -Execute powershell.exe -Argument '-WindowStyle Hidden -ExecutionPolicy Bypass -File "<full path to>\scripts\keepalive.ps1"'
$trigger = New-ScheduledTaskTrigger -AtLogOn
Register-ScheduledTask -TaskName "MoSesKeepalive" -Action $action -Trigger $trigger
```

Remove it later with `Unregister-ScheduledTask -TaskName MoSesKeepalive -Confirm:$false`.

## Notes

- Output goes to the console and to `scripts\keepalive.log` (git-ignored).
- The target URLs are at the top of the script; update them if the Render services are recreated.
- Render's free plan gives 750 instance-hours per month for the whole workspace. Keeping
  several services awake around the clock can use that up; a paid plan avoids the limit.
- The computer must be on for the pings to run. For 24/7 use an external pinger or a paid plan.
