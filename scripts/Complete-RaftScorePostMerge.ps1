[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$MergedCommit,
    [string]$MavenSettings = 'F:\dev\RaftScore\target\master-runtime-c965b7c\crew-registration-build-settings.xml'
)
$ErrorActionPreference = 'Stop'
$stage = 'initialization'
$runtime = 'F:\dev\RaftScore\target\master-runtime-c965b7c'
$java = 'C:\Users\dima\.jdks\openjdk-26.0.1\bin\java.exe'
$container = 'raftscore-master-test-93b4f5d'
$pidFile = Join-Path $runtime 'app-pid.txt'
$jarFile = Join-Path $runtime 'app-jar.txt'
$outLog = Join-Path $runtime 'app-out.log'
$errLog = Join-Path $runtime 'app-err.log'
$intentFile = Join-Path $runtime 'app-launch-intent.json'

function Invoke-Git([string[]]$Arguments) {
    $result = & git @Arguments
    if ($LASTEXITCODE -ne 0) { throw "git failed: $($Arguments -join ' ')" }
    return $result
}
function Get-Listener {
    return @(Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue)
}
function Get-VerifiedProcess {
    $listeners = @(Get-Listener)
    if ($listeners.Count -eq 0) { return $null }
    if ($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1') { throw 'Unexpected listener on port 8081' }
    if (!(Test-Path -LiteralPath $pidFile)) { throw 'Listener has no PID file' }
    $pidText = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if ($pidText -notmatch '^\d+$' -or [int]$pidText -ne $listeners[0].OwningProcess) { throw 'PID file does not match listener' }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$pidText" -ErrorAction Stop
    if (!$process -or $process.ExecutablePath -ine $java -or $process.CommandLine -notmatch '(?i)(?:^|\s)-jar\s+(?:"([^"]+)"|(\S+))') { throw 'Listener is not the expected Java JAR process' }
    $path = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
    $path = (Resolve-Path -LiteralPath $path).Path
    if ((Split-Path -Parent $path).TrimEnd('\') -ine $runtime.TrimEnd('\') -or [IO.Path]::GetExtension($path) -ine '.jar') { throw 'Running JAR is outside the runtime directory' }
    return [pscustomobject]@{ Id = [int]$pidText; Jar = $path }
}
function Assert-Database {
    $inspect = & docker inspect $container | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or @($inspect).Count -ne 1 -or $inspect.Name -ne "/$container") { throw 'Expected database container is missing' }
    $bindings = @($inspect.HostConfig.PortBindings.'5432/tcp')
    if ($bindings.Count -ne 1 -or $bindings[0].HostIp -ne '127.0.0.1' -or $bindings[0].HostPort -ne '15433') { throw 'Configured database mapping differs from runbook' }
    if (!$inspect.State.Running) { & docker start $container | Out-Null; if ($LASTEXITCODE -ne 0) { throw 'Database start failed' } }
    $inspect = & docker inspect $container | ConvertFrom-Json
    $bindings = @($inspect.NetworkSettings.Ports.'5432/tcp')
    if ($LASTEXITCODE -ne 0 -or !$inspect.State.Running -or $bindings.Count -ne 1 -or $bindings[0].HostIp -ne '127.0.0.1' -or $bindings[0].HostPort -ne '15433') { throw 'Live database mapping differs from runbook' }
    for ($i = 0; $i -lt 30; $i++) { & docker exec $container pg_isready -U raftscore -d raftscore | Out-Null; if ($LASTEXITCODE -eq 0) { return }; Start-Sleep -Seconds 1 }
    throw 'Database is not ready'
}
function Restore-InterruptedLaunch([string]$ExpectedJar, [string]$ExpectedHash) {
    if (!(Test-Path -LiteralPath $intentFile)) { return }
    try { $intent = Get-Content -LiteralPath $intentFile -Raw | ConvertFrom-Json } catch { throw 'Launch intent is unreadable' }
    if ($intent.Jar -ine $ExpectedJar -or $intent.Hash -ne $ExpectedHash -or $intent.Token -notmatch '^[a-f0-9]{32}$') { return }
    $tokenArgument = '-Draftscore.launchToken=' + $intent.Token
    $matches = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        $_.ExecutablePath -ieq $java -and $_.CommandLine -like ('*' + $tokenArgument + '*')
    })
    if ($matches.Count -eq 0) { return }
    if ($matches.Count -ne 1) { throw 'Launch intent matches multiple processes' }
    $process = $matches[0]
    $expectedCommand = '"' + $java + '" -Xms64m -Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=2 ' + $tokenArgument + ' -jar "' + $ExpectedJar + '" --server.address=127.0.0.1 --server.port=8081'
    if ($process.CommandLine -ine $expectedCommand -and $process.CommandLine -ine ($expectedCommand.Substring(1).Replace(('"' + ' -Xms64m'), ' -Xms64m'))) { throw 'Launch intent process command line differs from expected launch' }
    $listeners = @(Get-Listener)
    if ($listeners.Count -gt 1 -or ($listeners.Count -eq 1 -and ($listeners[0].LocalAddress -ne '127.0.0.1' -or $listeners[0].OwningProcess -ne $process.ProcessId))) { throw 'Launch intent process does not own the loopback listener' }
    if (Test-Path -LiteralPath $pidFile) {
        $existingPid = (Get-Content -LiteralPath $pidFile -Raw).Trim()
        if ($existingPid -ne [string]$process.ProcessId -and (Get-Process -Id $existingPid -ErrorAction SilentlyContinue)) { throw 'PID file identifies another live process' }
    }
    Set-Content -LiteralPath $pidFile -Value $process.ProcessId
}
function Get-PendingProcess([string]$ExpectedJar) {
    if (!(Test-Path -LiteralPath $pidFile)) { return $null }
    $pidText = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if ($pidText -notmatch '^\d+$') { throw 'Invalid PID file' }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$pidText" -ErrorAction Stop
    if (!$process) { return $null }
    if ($process.ExecutablePath -ine $java -or $process.CommandLine -notmatch '(?i)(?:^|\s)-jar\s+(?:"([^"]+)"|(\S+))') { throw 'PID file identifies an unrelated live process' }
    $path = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
    if ((Resolve-Path -LiteralPath $path).Path -ine $ExpectedJar) { throw 'PID file identifies a different live JAR' }
    return [pscustomobject]@{ Id = [int]$pidText; Jar = $ExpectedJar }
}
function Wait-Ready([int]$ProcessId, [string]$ExpectedJar, [string]$ExpectedHash) {
    for ($i = 0; $i -lt 90; $i++) {
        if (!(Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { throw "Application exited; inspect $outLog and $errLog" }
        try { Assert-Ready $ExpectedJar $ExpectedHash; return } catch { Start-Sleep -Seconds 1 }
    }
    throw "Application did not become ready; inspect $outLog and $errLog"
}
function Assert-Ready([string]$ExpectedJar, [string]$ExpectedHash) {
    $process = Get-VerifiedProcess
    if (!$process -or $process.Jar -ine $ExpectedJar -or (Get-FileHash -LiteralPath $ExpectedJar -Algorithm SHA256).Hash -ne $ExpectedHash) { throw 'Running JAR does not match the staged artifact' }
    if (!(Test-Path -LiteralPath $outLog) -or !(Select-String -LiteralPath $outLog -SimpleMatch 'Started RaftScoreApplication' -Quiet)) { throw 'Startup log does not confirm application startup' }
    $landingResponse = Invoke-WebRequest -Uri 'http://127.0.0.1:8081/' -UseBasicParsing -TimeoutSec 10
    $csrf = Invoke-WebRequest -Uri 'http://127.0.0.1:8081/api/auth/csrf' -UseBasicParsing -TimeoutSec 10
    if ($landingResponse.StatusCode -ne 200 -or $csrf.StatusCode -ne 200 -or [string]::IsNullOrWhiteSpace(($csrf.Content | ConvertFrom-Json).headerName)) { throw 'HTTP readiness checks failed' }
}
$mutex = [Threading.Mutex]::new($false, 'Local\RaftScorePostMerge-c965b7c')
$locked = $false
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (!$locked) { throw 'Another post-merge run is already active' }
    $stage = 'fetch and verify merged commit'
    Invoke-Git @('fetch', 'origin', 'master') | Out-Null
    $commit = (Invoke-Git @('rev-parse', '--verify', "$MergedCommit^{commit}")) | Select-Object -First 1
    & git merge-base --is-ancestor $commit origin/master
    if ($LASTEXITCODE -ne 0) { throw 'Merged commit is not an ancestor of origin/master' }
    $target = (Invoke-Git @('rev-parse', 'origin/master')) | Select-Object -First 1
    $stage = 'fast-forward local master'
    $worktree = $null; $candidate = $null
    foreach ($line in (Invoke-Git @('worktree', 'list', '--porcelain'))) {
        if ($line.StartsWith('worktree ')) { $candidate = $line.Substring(9) }
        if ($line -eq 'branch refs/heads/master') { $worktree = $candidate }
    }
    if (!$worktree) { throw 'No checked-out local master worktree found' }
    $dirty = @(Invoke-Git @('-C', $worktree, 'status', '--porcelain', '--untracked-files=normal'))
    if ($dirty.Count -gt 0) { throw "Local master worktree is dirty: $worktree" }
    Invoke-Git @('-C', $worktree, 'merge', '--ff-only', $target) | Out-Null
    if ((Invoke-Git @('-C', $worktree, 'rev-parse', 'HEAD')) -ne $target) { throw 'Local master did not reach fetched origin/master' }
    $stage = 'stage artifact'
    $jar = $null
    $manifest = Join-Path $runtime "raftscore-$target.sha256"
    $hash = if (Test-Path -LiteralPath $manifest) { (Get-Content -LiteralPath $manifest -Raw).Trim() } else { "" }
    if ($hash -match '^[A-F0-9]{64}$') { $jar = Join-Path $runtime "raftscore-$target-$hash.jar" }
    $valid = $jar -and (Test-Path -LiteralPath $jar) -and (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -eq $hash
    if (!$valid) {

        if (!(Test-Path -LiteralPath $MavenSettings)) { throw "Maven settings not found: $MavenSettings" }
        $build = Join-Path $runtime ("build-$target-" + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $build | Out-Null
        $archive = Join-Path $build 'source.tar'
        Invoke-Git @('archive', '--format=tar', "--output=$archive", $target) | Out-Null
        & tar -xf $archive -C $build
        if ($LASTEXITCODE -ne 0) { throw 'Source archive extraction failed' }
        $oldArgs = $env:MAVEN_ARGS; $oldHome = $env:JAVA_HOME; $oldOpts = $env:MAVEN_OPTS
        try {
            $env:MAVEN_ARGS = ''; $env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
            $env:MAVEN_OPTS = '-Xms64m -Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1'
            Push-Location (Join-Path $build 'frontend')
            try {
                & npm.cmd ci
                if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed' }
                & npm.cmd run build
                if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
            } finally { Pop-Location }
            Push-Location $build
            try { & mvn.cmd -B -s $MavenSettings '-Dskip.installnodenpm=true' '-Dskip.npm=true' '-DskipTests=true' package; if ($LASTEXITCODE -ne 0) { throw 'Maven package failed' } } finally { Pop-Location }
        } finally { $env:MAVEN_ARGS = $oldArgs; $env:JAVA_HOME = $oldHome; $env:MAVEN_OPTS = $oldOpts }
        $builtJar = Join-Path $build 'target\raft-score-0.0.1-SNAPSHOT.jar'
        if (!(Test-Path -LiteralPath $builtJar)) { throw 'Expected build artifact is missing' }
        $hash = (Get-FileHash -LiteralPath $builtJar -Algorithm SHA256).Hash
        $jar = Join-Path $runtime "raftscore-$target-$hash.jar"
        if (Test-Path -LiteralPath $jar) {
            if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $hash) { throw 'Existing staged artifact has an unexpected hash' }
        } else {
            $temporaryJar = Join-Path $runtime ('.staging-' + [guid]::NewGuid().ToString('N') + '.jar')
            Copy-Item -LiteralPath $builtJar -Destination $temporaryJar
            if ((Get-FileHash -LiteralPath $temporaryJar -Algorithm SHA256).Hash -ne $hash) { throw 'Staged copy hash mismatch' }
            [IO.File]::Move($temporaryJar, $jar)
        }
        $temporaryManifest = Join-Path $runtime ('.staging-' + [guid]::NewGuid().ToString('N') + '.sha256')
        [IO.File]::WriteAllText($temporaryManifest, $hash, [Text.Encoding]::ASCII)
        if (Test-Path -LiteralPath $manifest) { [IO.File]::Replace($temporaryManifest, $manifest, [NullString]::Value) } else { [IO.File]::Move($temporaryManifest, $manifest) }
    }
    $hash = (Get-Content -LiteralPath $manifest -Raw).Trim()
    $stage = 'verify database and current process'
    Assert-Database
    Restore-InterruptedLaunch $jar $hash
    $current = Get-VerifiedProcess
    if (!$current) { $current = Get-PendingProcess $jar }
    if ($current -and $current.Jar -ieq $jar) {
        $stage = 'verify running artifact'
        Wait-Ready $current.Id $jar $hash
    } else {
        $stage = 'verify startup prerequisites'
        if (!(Test-Path -LiteralPath $java -PathType Leaf)) { throw 'Java executable is missing' }
        $source = Join-Path $runtime 'source'
        if (!(Test-Path -LiteralPath $source -PathType Container)) { throw 'Runtime working directory is missing' }
        try {
            $env:RAFT_SCORE_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:15433/raftscore'
            $env:RAFT_SCORE_DATABASE_USERNAME = 'raftscore'
            $env:RAFT_SCORE_DATABASE_PASSWORD = (& docker exec $container printenv POSTGRES_PASSWORD)
            if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($env:RAFT_SCORE_DATABASE_PASSWORD)) { throw 'Database password unavailable' }
            $stage = 'record launch intent'
            $token = [guid]::NewGuid().ToString('N')
            $temporaryIntent = Join-Path $runtime ('.staging-' + $token + '.json')
            $intent = @{ Jar = $jar; Hash = $hash; Token = $token } | ConvertTo-Json -Compress
            [IO.File]::WriteAllText($temporaryIntent, $intent, [Text.Encoding]::UTF8)
            if (Test-Path -LiteralPath $intentFile) { [IO.File]::Replace($temporaryIntent, $intentFile, [NullString]::Value) } else { [IO.File]::Move($temporaryIntent, $intentFile) }
            if ($current) {
                $stage = 'stop previous application'
                Set-Content -LiteralPath $jarFile -Value $current.Jar
                Stop-Process -Id $current.Id -ErrorAction Stop
                for ($i = 0; $i -lt 30 -and (Get-Process -Id $current.Id -ErrorAction SilentlyContinue); $i++) { Start-Sleep -Seconds 1 }
                if ((Get-Process -Id $current.Id -ErrorAction SilentlyContinue) -or @(Get-Listener).Count -ne 0) { throw 'Previous process or listener did not stop' }
            }
            if (@(Get-Listener).Count -ne 0) { throw 'Port 8081 is occupied' }
            $stage = 'start application'
            $p = Start-Process -FilePath $java -ArgumentList @('-Xms64m', '-Xmx256m', '-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1', '-XX:ActiveProcessorCount=2', ('-Draftscore.launchToken=' + $token), '-jar', ('"' + $jar + '"'), '--server.address=127.0.0.1', '--server.port=8081') -WorkingDirectory $source -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
            Set-Content -LiteralPath $pidFile -Value $p.Id
        } finally { Remove-Item Env:RAFT_SCORE_DATABASE_PASSWORD -ErrorAction SilentlyContinue; Remove-Item Env:RAFT_SCORE_INITIAL_ADMINISTRATOR_PASSWORD -ErrorAction SilentlyContinue }
        $stage = 'verify startup and HTTP'
        Wait-Ready $p.Id $jar $hash
    }
    $stage = 'record verified artifact'
    Set-Content -LiteralPath $jarFile -Value $jar
    Write-Output "Verified master commit: $target"
    Write-Output 'Open RaftScore: http://127.0.0.1:8081/'
} catch {
    throw "Post-merge stage '$stage' failed: $($_.Exception.Message)"
} finally {
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
