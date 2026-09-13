#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateRange(1, 300)][int]$DurationSeconds = 30,
    [ValidateRange(1, 60)][int]$WarmupSeconds = 5,
    [ValidateRange(1, 10)][int]$Repetitions = 3,
    [switch]$NthOnly,
    [switch]$ExplainOnly
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$resultDir = Join-Path $root 'results'
$runStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$project = "error-spike-stage5-$runStamp"
$appImage = "error-spike-detection-lab:stage5-$runStamp"
$composeFiles = @('-f', 'compose.yaml', '-f', 'compose.performance.yaml')
$rows = [System.Collections.Generic.List[object]]::new()
$sequence = 0
$created = $false
$indexName = 'idx_error_event_source_received_at'
$environmentNames = @(
    'DB_PORT', 'REDIS_PORT', 'WIREMOCK_PORT', 'APP_PORT', 'APP_IMAGE', 'APP_PROFILES',
    'APP_JAVA_TOOL_OPTIONS', 'PERFORMANCE_RESULTS_DIR', 'K6_RPS', 'K6_DURATION',
    'K6_SOURCE', 'K6_RUN_ID', 'K6_EXPECTED_COUNT_PATH', 'K6_SCENARIO_NAME',
    'K6_SUMMARY_PATH', 'K6_ALLOW_DROPPED_ITERATIONS', 'K6_NTH_MODE'
)
$savedEnvironment = @{}
foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Assert-Stage5([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

function Invoke-Compose {
    & docker compose -p $project @composeFiles @args
    Assert-Stage5 ($LASTEXITCODE -eq 0) "Compose command failed: $($args -join ' ')"
}

function Invoke-Postgres([string]$Sql) {
    $output = & docker compose -p $project @composeFiles exec -T postgres `
        psql -X -v ON_ERROR_STOP=1 -U error_app -d error_detection -Atc $Sql
    Assert-Stage5 ($LASTEXITCODE -eq 0) 'PostgreSQL command failed'
    return (($output | ForEach-Object { [string]$_ }) -join "`n").Trim()
}

function Wait-App {
    $deadline = (Get-Date).AddSeconds(120)
    do {
        try {
            $response = Invoke-WebRequest "$script:baseUrl/actuator/health/liveness" -TimeoutSec 5 -SkipHttpErrorCheck
            if ($response.StatusCode -eq 200) { return }
        } catch [System.Net.Http.HttpRequestException] {
        } catch [System.Threading.Tasks.TaskCanceledException] {
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw 'Application liveness wait timed out'
}

function Set-IndexMode([bool]$Enabled) {
    if ($Enabled) {
        Invoke-Postgres "CREATE INDEX IF NOT EXISTS $indexName ON error_event (source, received_at DESC); ANALYZE error_event;" | Out-Null
        $expected = 1
    } else {
        Invoke-Postgres "DROP INDEX IF EXISTS $indexName; ANALYZE error_event;" | Out-Null
        $expected = 0
    }
    $actual = [int](Invoke-Postgres "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND tablename='error_event' AND indexname='$indexName';")
    Assert-Stage5 ($actual -eq $expected) "Index catalog state mismatch: expected $expected, actual $actual"
    $primaryKeyCount = [int](Invoke-Postgres "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND tablename='error_event' AND indexname='error_event_pkey';")
    Assert-Stage5 ($primaryKeyCount -eq 1) 'Primary key index must remain present'
}

function Reset-RunState([bool]$RedisEnabled) {
    Invoke-Postgres 'TRUNCATE TABLE alert_delivery, alert_cooldown, error_event RESTART IDENTITY;' | Out-Null
    if ($RedisEnabled) {
        $redisResult = & docker compose -p $project @composeFiles exec -T redis redis-cli FLUSHDB
        Assert-Stage5 ($LASTEXITCODE -eq 0 -and ($redisResult -join '').Trim() -eq 'OK') 'Redis reset failed'
    }
    Invoke-RestMethod "$script:wiremockUrl/__admin/requests" -Method Delete -TimeoutSec 10 | Out-Null
}

function Start-Application([bool]$RedisEnabled, [bool]$CooldownEnabled) {
    if ($RedisEnabled) {
        Invoke-Compose up -d --wait redis
    } else {
        & docker compose -p $project @composeFiles stop redis | Out-Null
        Assert-Stage5 ($LASTEXITCODE -eq 0) 'Redis stop failed'
    }
    $env:APP_PROFILES = if ($CooldownEnabled) { 'local' } else { 'local,benchmark' }
    & docker compose -p $project @composeFiles rm -sf app | Out-Null
    Assert-Stage5 ($LASTEXITCODE -eq 0) 'Previous application container cleanup failed'
    Invoke-Compose up -d --no-deps app
    Wait-App
}

function Get-K6Value($Summary, [string]$MetricName, [string]$ValueName, [double]$Default = 0.0) {
    $metric = $Summary.metrics.PSObject.Properties[$MetricName]
    if ($null -eq $metric) { return $Default }
    $value = $metric.Value.values.PSObject.Properties[$ValueName]
    if ($null -eq $value) { return $Default }
    return [double]$value.Value
}

function Invoke-K6(
    [string]$Scenario,
    [int]$Repetition,
    [string]$Source,
    [string]$RunId,
    [string]$ExpectedPath,
    [int]$Seconds,
    [bool]$Warmup,
    [bool]$NthMode = $false
) {
    $label = if ($Warmup) { "warmup-$Scenario-run-$Repetition" } else { "$Scenario-run-$Repetition" }
    $env:K6_RPS = '100'
    $env:K6_DURATION = "${Seconds}s"
    $env:K6_SOURCE = $Source
    $env:K6_RUN_ID = $RunId
    $env:K6_EXPECTED_COUNT_PATH = $ExpectedPath
    $env:K6_SCENARIO_NAME = ($label -replace '-', '_')
    $env:K6_SUMMARY_PATH = "/results/k6/$label.json"
    $env:K6_ALLOW_DROPPED_ITERATIONS = $Warmup.ToString().ToLowerInvariant()
    $env:K6_NTH_MODE = $NthMode.ToString().ToLowerInvariant()
    $stdoutPath = Join-Path $resultDir "k6/$label.stdout.log"
    $stderrPath = Join-Path $resultDir "k6/$label.stderr.log"
    $arguments = @('compose', '-p', $project) + $composeFiles + @(
        '--profile', 'measurement', 'run', '--rm', 'k6', 'run', '/scripts/error-load.js'
    )
    $process = Start-Process docker -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $process.WaitForExit()
    $summaryPath = Join-Path $resultDir "k6/$label.json"
    Assert-Stage5 (Test-Path -LiteralPath $summaryPath) "k6 summary was not created: $label"
    $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json -Depth 100
    if (-not $Warmup) {
        Assert-Stage5 ($process.ExitCode -eq 0) "k6 measurement failed: $label"
    } else {
        $checkRate = Get-K6Value $summary 'checks' 'rate'
        Assert-Stage5 ($checkRate -gt 0.999) "k6 warm-up checks failed: $label"
    }
    return $summary
}

function Wait-AlertDrain {
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $pending = [int](Invoke-Postgres "SELECT count(*) FROM alert_delivery WHERE status='PENDING';")
        if ($pending -eq 0) { return }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw 'Alert executor drain timed out'
}

function Save-AlertRows([string]$RunId, [string]$Scenario, [int]$Repetition) {
    $sql = @"
SELECT COALESCE(json_agg(row_to_json(delivery) ORDER BY delivery.queued_at), '[]'::json)
FROM (
  SELECT id, source, run_id, rolling_count, count_path, cooldown_path, status,
         attempt_count, threshold_event_received_at, detected_at, queued_at,
         first_attempt_at, sent_at, last_error
  FROM alert_delivery
  WHERE run_id = '$RunId'
) delivery;
"@
    $json = Invoke-Postgres $sql
    $path = Join-Path $resultDir "alerts/$Scenario-run-$Repetition.json"
    Set-Content -LiteralPath $path -Value $json -Encoding utf8NoBOM
}

function Get-AlertAggregate([string]$RunId) {
    $sql = @"
SELECT concat_ws('|',
  count(*),
  coalesce(sum(attempt_count), 0),
  count(*) FILTER (WHERE status='SENT'),
  count(*) FILTER (WHERE status='FAILED'),
  count(*) FILTER (WHERE status='PENDING'),
  coalesce((SELECT round(extract(epoch FROM (first.sent_at - first.threshold_event_received_at)) * 1000, 3)
            FROM alert_delivery first
            WHERE first.run_id='$RunId' AND first.sent_at IS NOT NULL
            ORDER BY first.rolling_count, first.queued_at
            LIMIT 1), 0)
)
FROM alert_delivery
WHERE run_id='$RunId';
"@
    $parts = (Invoke-Postgres $sql) -split '\|'
    Assert-Stage5 ($parts.Count -eq 6) 'Alert aggregate parse failed'
    return [pscustomobject]@{
        Queued = [int]$parts[0]
        Attempts = [int]$parts[1]
        Sent = [int]$parts[2]
        Failed = [int]$parts[3]
        Pending = [int]$parts[4]
        ThresholdToAlertMs = [double]::Parse($parts[5], [Globalization.CultureInfo]::InvariantCulture)
    }
}

function Get-FirstAlertRollingCount([string]$RunId) {
    return [int](Invoke-Postgres "SELECT rolling_count FROM alert_delivery WHERE run_id='$RunId' ORDER BY queued_at LIMIT 1;")
}

function Save-Explain([string]$Scenario, [string]$Source, [string]$IndexMode) {
    $path = Join-Path $resultDir "explain/fallback-index-$($IndexMode.ToLowerInvariant()).json"
    $bounds = (Invoke-Postgres "SELECT min(received_at)::text || '|' || max(received_at)::text FROM error_event WHERE source='$Source';") -split '\|'
    Assert-Stage5 ($bounds.Count -eq 2) 'EXPLAIN bounds were not found'
    $from = [DateTimeOffset]::Parse($bounds[1], [Globalization.CultureInfo]::InvariantCulture).AddSeconds(-60).ToString('o')
    $to = [DateTimeOffset]::Parse($bounds[1], [Globalization.CultureInfo]::InvariantCulture).ToString('o')
    $sql = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT count(*) FROM error_event WHERE source='$Source' AND received_at > '$from'::timestamptz AND received_at <= '$to'::timestamptz;"
    $json = Invoke-Postgres $sql
    Set-Content -LiteralPath $path -Value $json -Encoding utf8NoBOM
    Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -Depth 100 | Out-Null
    return $path
}

function Invoke-Scenario($Definition, [int]$Repetition) {
    $script:sequence++
    $scenario = $Definition.Name
    $source = "s5-$($Definition.Code)-r$Repetition-$runStamp"
    $runId = "stage5-$scenario-run-$Repetition-$runStamp"
    $startedAt = [DateTimeOffset]::Now
    $status = 'SUCCESS'
    $failureMessage = ''
    try {
        Start-Application $Definition.RedisEnabled $Definition.CooldownEnabled
        Set-IndexMode $Definition.IndexEnabled
        Reset-RunState $Definition.RedisEnabled
        Invoke-K6 $scenario $Repetition "$source-warm" "$runId-warm" $Definition.ExpectedPath $WarmupSeconds $true | Out-Null
        Wait-AlertDrain
        Reset-RunState $Definition.RedisEnabled
        $startedAt = [DateTimeOffset]::Now
        $summary = Invoke-K6 $scenario $Repetition $source $runId $Definition.ExpectedPath $DurationSeconds $false
        Wait-AlertDrain
        $endedAt = [DateTimeOffset]::Now
        $alerts = Get-AlertAggregate $runId
        Assert-Stage5 ($alerts.Pending -eq 0) "Pending alerts remain: $scenario run $Repetition"
        Save-AlertRows $runId $scenario $Repetition
        $explainPath = ''
        if ($scenario -like 'db-fallback-*') {
            $savedPath = Save-Explain $scenario $source $Definition.IndexMode
            $explainPath = [IO.Path]::GetRelativePath($root, $savedPath).Replace('\', '/')
        }
        $sentEvents = [int](Get-K6Value $summary 'iterations' 'count')
        $failedRate = Get-K6Value $summary 'http_req_failed' 'rate'
        $failedEvents = [int][Math]::Round($sentEvents * $failedRate)
        $queuedFromResponse = [int](Get-K6Value $summary 'alert_queued' 'count')
        Assert-Stage5 ($queuedFromResponse -eq $alerts.Queued) "Queued response/DB mismatch: $scenario run $Repetition"
        $rows.Add([pscustomobject]@{
            runId = $runId
            scenario = $scenario
            repetition = $Repetition
            sequence = $script:sequence
            status = $status
            failureMessage = $failureMessage
            startedAt = $startedAt.ToString('o')
            endedAt = $endedAt.ToString('o')
            durationSeconds = $DurationSeconds
            rate = 100
            sentEvents = $sentEvents
            failedEvents = $failedEvents
            droppedIterations = [int](Get-K6Value $summary 'dropped_iterations' 'count')
            apiP50Ms = [Math]::Round((Get-K6Value $summary 'http_req_duration' 'p(50)'), 3)
            apiP95Ms = [Math]::Round((Get-K6Value $summary 'http_req_duration' 'p(95)'), 3)
            apiP99Ms = [Math]::Round((Get-K6Value $summary 'http_req_duration' 'p(99)'), 3)
            apiMaxMs = [Math]::Round((Get-K6Value $summary 'http_req_duration' 'max'), 3)
            counterPath = $Definition.ExpectedPath
            counterP95Ms = [Math]::Round((Get-K6Value $summary 'counter_duration_ms' 'p(95)'), 3)
            fallbackCount = [int](Get-K6Value $summary 'count_path_db_fallback' 'count')
            threshold = 100
            windowSeconds = 60
            cooldownSeconds = 60
            cooldownEnabled = $Definition.CooldownEnabled
            alertQueued = $alerts.Queued
            alertAttempts = $alerts.Attempts
            alertSent = $alerts.Sent
            alertFailed = $alerts.Failed
            alertSuppressed = [int](Get-K6Value $summary 'alert_suppressed' 'count')
            thresholdToAlertMs = [Math]::Round($alerts.ThresholdToAlertMs, 3)
            indexMode = $Definition.IndexMode
            explainPath = $explainPath
            environmentPath = 'results/environment.json'
        })
    } catch {
        $status = 'FAILED'
        $failureMessage = $_.Exception.Message
        $failurePath = Join-Path $resultDir "failed-$scenario-run-$Repetition.log"
        Set-Content -LiteralPath $failurePath -Value ($_ | Out-String) -Encoding utf8NoBOM
        try {
            & docker compose -p $project @composeFiles logs --no-color app | Set-Content -LiteralPath (Join-Path $resultDir "app-$scenario-run-$Repetition.log") -Encoding utf8NoBOM
        } catch {
        }
        $rows.Add([pscustomobject]@{
            runId = $runId; scenario = $scenario; repetition = $Repetition; sequence = $script:sequence
            status = $status; failureMessage = $failureMessage; startedAt = $startedAt.ToString('o')
            endedAt = [DateTimeOffset]::Now.ToString('o'); durationSeconds = $DurationSeconds; rate = 100
            sentEvents = 0; failedEvents = 0; droppedIterations = 0; apiP50Ms = 0; apiP95Ms = 0
            apiP99Ms = 0; apiMaxMs = 0; counterPath = $Definition.ExpectedPath; counterP95Ms = 0
            fallbackCount = 0; threshold = 100; windowSeconds = 60; cooldownSeconds = 60
            cooldownEnabled = $Definition.CooldownEnabled; alertQueued = 0; alertAttempts = 0
            alertSent = 0; alertFailed = 0; alertSuppressed = 0; thresholdToAlertMs = 0
            indexMode = $Definition.IndexMode; explainPath = ''; environmentPath = 'results/environment.json'
        })
        throw
    } finally {
        $rows | Export-Csv -LiteralPath (Join-Path $resultDir 'raw-runs.csv') -NoTypeInformation -Encoding utf8NoBOM
    }
}

function Invoke-NthScenario($Definition, [int]$Repetition) {
    $script:sequence++
    $scenario = $Definition.Name
    $source = "s5-$($Definition.Code)-r$Repetition-$runStamp"
    $runId = "stage5-$scenario-run-$Repetition-$runStamp"
    Start-Application $Definition.RedisEnabled $true
    Set-IndexMode $true
    Reset-RunState $Definition.RedisEnabled
    Invoke-K6 "$scenario-preheat" $Repetition "$source-warm" "$runId-warm" $Definition.ExpectedPath $WarmupSeconds $true | Out-Null
    Wait-AlertDrain
    Reset-RunState $Definition.RedisEnabled
    $startedAt = [DateTimeOffset]::Now
    $summary = Invoke-K6 $scenario $Repetition $source $runId $Definition.ExpectedPath 0 $false $true
    Wait-AlertDrain
    $endedAt = [DateTimeOffset]::Now
    $alerts = Get-AlertAggregate $runId
    $firstRollingCount = Get-FirstAlertRollingCount $runId
    Assert-Stage5 (([int](Get-K6Value $summary 'iterations' 'count')) -eq 100) "N-th experiment did not complete 100 requests: $scenario run $Repetition"
    Assert-Stage5 ($alerts.Queued -eq 1 -and $alerts.Sent -eq 1 -and $alerts.Failed -eq 0) "N-th alert result mismatch: $scenario run $Repetition"
    Assert-Stage5 ($firstRollingCount -eq 100) "N-th alert rolling count was $firstRollingCount instead of 100: $scenario run $Repetition"
    Save-AlertRows $runId $scenario $Repetition
    $actualDuration = [Math]::Round(([double]$summary.state.testRunDurationMs / 1000), 3)
    $rows.Add([pscustomobject]@{
        runId = $runId
        scenario = $scenario
        repetition = $Repetition
        sequence = $script:sequence
        status = 'SUCCESS'
        failureMessage = ''
        startedAt = $startedAt.ToString('o')
        endedAt = $endedAt.ToString('o')
        durationSeconds = $actualDuration
        rate = [Math]::Round((Get-K6Value $summary 'iterations' 'rate'), 3)
        sentEvents = 100
        failedEvents = [int][Math]::Round(100 * (Get-K6Value $summary 'http_req_failed' 'rate'))
        droppedIterations = 0
        apiP50Ms = [Math]::Round((Get-K6Value $summary 'http_req_duration' 'p(50)'), 3)
        apiP95Ms = [Math]::Round((Get-K6Value $summary 'http_req_duration' 'p(95)'), 3)
        apiP99Ms = [Math]::Round((Get-K6Value $summary 'http_req_duration' 'p(99)'), 3)
        apiMaxMs = [Math]::Round((Get-K6Value $summary 'http_req_duration' 'max'), 3)
        counterPath = $Definition.ExpectedPath
        counterP95Ms = [Math]::Round((Get-K6Value $summary 'counter_duration_ms' 'p(95)'), 3)
        fallbackCount = [int](Get-K6Value $summary 'count_path_db_fallback' 'count')
        threshold = 100
        windowSeconds = 60
        cooldownSeconds = 60
        cooldownEnabled = $true
        alertQueued = $alerts.Queued
        alertAttempts = $alerts.Attempts
        alertSent = $alerts.Sent
        alertFailed = $alerts.Failed
        alertSuppressed = [int](Get-K6Value $summary 'alert_suppressed' 'count')
        thresholdToAlertMs = [Math]::Round($alerts.ThresholdToAlertMs, 3)
        indexMode = 'ON'
        explainPath = ''
        environmentPath = 'results/environment.json'
    })
    $rows | Export-Csv -LiteralPath (Join-Path $resultDir 'raw-runs.csv') -NoTypeInformation -Encoding utf8NoBOM
}

function Write-Summary {
    $successful = @($rows | Where-Object status -eq 'SUCCESS')
    $summaryRows = foreach ($group in ($successful | Group-Object scenario)) {
        $items = @($group.Group)
        [pscustomobject]@{
            scenario = $group.Name
            runs = $items.Count
            apiP95MsAverage = [Math]::Round(($items.apiP95Ms | Measure-Object -Average).Average, 3)
            apiP95MsMin = [Math]::Round(($items.apiP95Ms | Measure-Object -Minimum).Minimum, 3)
            apiP95MsMax = [Math]::Round(($items.apiP95Ms | Measure-Object -Maximum).Maximum, 3)
            counterP95MsAverage = [Math]::Round(($items.counterP95Ms | Measure-Object -Average).Average, 3)
            thresholdToAlertMsAverage = [Math]::Round(($items.thresholdToAlertMs | Measure-Object -Average).Average, 3)
            thresholdToAlertMsMin = [Math]::Round(($items.thresholdToAlertMs | Measure-Object -Minimum).Minimum, 3)
            thresholdToAlertMsMax = [Math]::Round(($items.thresholdToAlertMs | Measure-Object -Maximum).Maximum, 3)
            sentEventsAverage = [Math]::Round(($items.sentEvents | Measure-Object -Average).Average, 3)
            failedEventsTotal = ($items.failedEvents | Measure-Object -Sum).Sum
            droppedIterationsTotal = ($items.droppedIterations | Measure-Object -Sum).Sum
            alertQueuedAverage = [Math]::Round(($items.alertQueued | Measure-Object -Average).Average, 3)
            alertAttemptsAverage = [Math]::Round(($items.alertAttempts | Measure-Object -Average).Average, 3)
            alertSentAverage = [Math]::Round(($items.alertSent | Measure-Object -Average).Average, 3)
            alertFailedTotal = ($items.alertFailed | Measure-Object -Sum).Sum
            alertSuppressedAverage = [Math]::Round(($items.alertSuppressed | Measure-Object -Average).Average, 3)
        }
    }
    $summaryRows | Sort-Object scenario | Export-Csv -LiteralPath (Join-Path $resultDir 'summary.csv') -NoTypeInformation -Encoding utf8NoBOM
}

try {
    Set-Location $root
    foreach ($subdirectory in @('k6', 'explain', 'alerts')) {
        New-Item -ItemType Directory -Path (Join-Path $resultDir $subdirectory) -Force | Out-Null
    }
    $env:DB_PORT = [string](Get-FreePort)
    $env:REDIS_PORT = [string](Get-FreePort)
    $env:WIREMOCK_PORT = [string](Get-FreePort)
    $env:APP_PORT = [string](Get-FreePort)
    $env:APP_IMAGE = $appImage
    $env:APP_JAVA_TOOL_OPTIONS = '-XX:MaxRAMPercentage=75.0 -XX:ActiveProcessorCount=1'
    $env:PERFORMANCE_RESULTS_DIR = $resultDir
    $script:baseUrl = "http://127.0.0.1:$($env:APP_PORT)"
    $script:wiremockUrl = "http://127.0.0.1:$($env:WIREMOCK_PORT)"

    & .\gradlew.bat --no-daemon bootJar
    Assert-Stage5 ($LASTEXITCODE -eq 0) 'bootJar failed'
    Invoke-Compose --profile measurement pull postgres redis wiremock k6
    Invoke-Compose build --pull app
    Invoke-Compose up -d --wait postgres redis wiremock
    $created = $true

    $os = Get-CimInstance Win32_OperatingSystem
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $environment = [ordered]@{
        capturedAt = [DateTimeOffset]::Now.ToString('o')
        os = "$($os.Caption) $($os.Version)"
        cpu = $cpu.Name.Trim()
        logicalProcessors = [int]$cpu.NumberOfLogicalProcessors
        ramBytes = [int64]$os.TotalVisibleMemorySize * 1024
        java = ((& java -version 2>&1) -join ' ')
        springBoot = '3.5.16'
        gradle = ((& .\gradlew.bat --version) | Select-String '^Gradle ' | ForEach-Object { $_.Line.Trim() })
        postgresqlImage = 'postgres:16.8-alpine'
        redisImage = 'redis:7.4.2-alpine'
        wiremockImage = 'wiremock/wiremock:3.13.1'
        k6 = ((& docker run --rm grafana/k6:1.6.1 version) -join ' ').Trim()
        dockerCli = ((& docker version --format '{{.Client.Version}}') -join '').Trim()
        dockerEngine = ((& docker version --format '{{.Server.Version}}') -join '').Trim()
        dockerComposeCli = ((& docker compose version --short) -join '').Trim()
        appCpuLimit = 1.0
        appMemoryLimitBytes = 536870912
        postgresCpuLimit = 1.0
        postgresMemoryLimitBytes = 1073741824
        redisCpuLimit = 0.5
        redisMemoryLimitBytes = 268435456
        k6CpuLimit = 1.0
        k6MemoryLimitBytes = 536870912
        javaToolOptions = $env:APP_JAVA_TOOL_OPTIONS
        rps = 100
        warmupSeconds = $WarmupSeconds
        measurementSeconds = $DurationSeconds
        repetitions = $Repetitions
        cpuUsageSampling = 'not collected; container resource limits and host hardware are recorded'
        notes = 'Synthetic local data only. Each run truncates benchmark tables, flushes Redis when active, and resets the WireMock request journal.'
    }
    $environmentPath = Join-Path $resultDir 'environment.json'
    if ((-not $NthOnly -and -not $ExplainOnly) -or -not (Test-Path -LiteralPath $environmentPath)) {
        $environment | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $environmentPath -Encoding utf8NoBOM
    }

    $definitions = @(
        [pscustomobject]@{ Name='redis-cooldown-on'; Code='rcon'; RedisEnabled=$true; CooldownEnabled=$true; IndexEnabled=$true; IndexMode='ON'; ExpectedPath='REDIS' },
        [pscustomobject]@{ Name='redis-cooldown-off'; Code='rcoff'; RedisEnabled=$true; CooldownEnabled=$false; IndexEnabled=$true; IndexMode='ON'; ExpectedPath='REDIS' },
        [pscustomobject]@{ Name='db-fallback-index-on'; Code='dbon'; RedisEnabled=$false; CooldownEnabled=$true; IndexEnabled=$true; IndexMode='ON'; ExpectedPath='DB_FALLBACK' },
        [pscustomobject]@{ Name='db-fallback-index-off'; Code='dboff'; RedisEnabled=$false; CooldownEnabled=$true; IndexEnabled=$false; IndexMode='OFF'; ExpectedPath='DB_FALLBACK' }
    )
    if ($ExplainOnly) {
        $explainMetadata = [System.Collections.Generic.List[object]]::new()
        foreach ($definition in @($definitions | Where-Object Name -like 'db-fallback-*')) {
            $scenario = "explain-$($definition.Name)"
            $source = "s5-explain-$($definition.Code)-$runStamp"
            $explainRunId = "stage5-$scenario-$runStamp"
            Write-Host "[EXPLAIN] $($definition.IndexMode)"
            Start-Application $false $true
            Set-IndexMode $definition.IndexEnabled
            Reset-RunState $false
            Invoke-K6 "$scenario-preheat" 1 "$source-warm" "$explainRunId-warm" 'DB_FALLBACK' $WarmupSeconds $true | Out-Null
            Wait-AlertDrain
            Reset-RunState $false
            $startedAt = [DateTimeOffset]::Now
            $summary = Invoke-K6 $scenario 1 $source $explainRunId 'DB_FALLBACK' $DurationSeconds $false
            Wait-AlertDrain
            $savedPath = Save-Explain $scenario $source $definition.IndexMode
            $explainMetadata.Add([pscustomobject]@{
                runId = $explainRunId
                scenario = $scenario
                source = $source
                indexMode = $definition.IndexMode
                startedAt = $startedAt.ToString('o')
                endedAt = [DateTimeOffset]::Now.ToString('o')
                durationSeconds = $DurationSeconds
                targetRate = 100
                completedRequests = [int](Get-K6Value $summary 'iterations' 'count')
                droppedIterations = [int](Get-K6Value $summary 'dropped_iterations' 'count')
                explainPath = [IO.Path]::GetRelativePath($root, $savedPath).Replace('\', '/')
            })
        }
        $explainMetadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $resultDir 'explain/metadata.json') -Encoding utf8NoBOM
    } elseif ($NthOnly -and (Test-Path -LiteralPath (Join-Path $resultDir 'raw-runs.csv'))) {
        foreach ($existingRow in (Import-Csv -LiteralPath (Join-Path $resultDir 'raw-runs.csv') | Where-Object scenario -notlike 'nth-*')) {
            $rows.Add($existingRow)
        }
        $script:sequence = $rows.Count
    }
    $initialRows = $rows.Count
    if (-not $NthOnly -and -not $ExplainOnly) {
        for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
            $ordered = if ($repetition % 2 -eq 0) { @($definitions[3..0]) } else { $definitions }
            foreach ($definition in $ordered) {
                Write-Host "[$($script:sequence + 1)/$($Repetitions * 6)] $($definition.Name) run $repetition"
                Invoke-Scenario $definition $repetition
            }
        }
    }
    $nthDefinitions = @(
        [pscustomobject]@{ Name='nth-alert-redis'; Code='nth-r'; RedisEnabled=$true; ExpectedPath='REDIS' },
        [pscustomobject]@{ Name='nth-alert-db-fallback'; Code='nth-db'; RedisEnabled=$false; ExpectedPath='DB_FALLBACK' }
    )
    if (-not $ExplainOnly) {
        for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
            foreach ($definition in $nthDefinitions) {
                Write-Host "[$($script:sequence + 1)/$($Repetitions * 6)] $($definition.Name) run $repetition"
                Invoke-NthScenario $definition $repetition
            }
        }
        Write-Summary
        $expectedRows = if ($NthOnly) { $initialRows + ($Repetitions * 2) } else { $Repetitions * 6 }
        Assert-Stage5 ($rows.Count -eq $expectedRows) 'Unexpected raw run count'
        Assert-Stage5 (@($rows | Where-Object status -ne 'SUCCESS').Count -eq 0) 'One or more measurements failed'
    }
    Assert-Stage5 ((Test-Path -LiteralPath (Join-Path $resultDir 'explain/fallback-index-on.json'))) 'Index ON EXPLAIN missing'
    Assert-Stage5 ((Test-Path -LiteralPath (Join-Path $resultDir 'explain/fallback-index-off.json'))) 'Index OFF EXPLAIN missing'
    Write-Host "Stage 5 measurement completed: $resultDir"
} finally {
    if ($created) {
        try { Set-IndexMode $true } catch { Write-Warning "Index restore failed: $($_.Exception.Message)" }
        & docker compose -p $project @composeFiles down -v --remove-orphans | Out-Null
        & docker image rm $appImage | Out-Null
    }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    Set-Location $root
}
