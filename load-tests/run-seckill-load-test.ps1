param(
    [int[]]$Concurrency = @(100, 500, 1000),
    [int]$AppPort = 0,
    [int]$ManagementPort = 0,
    [int]$MySqlPort = 0,
    [int]$RedisPort = 0,
    [string]$ProjectName = "",
    [int]$VoucherIdBase = 9100,
    [int]$PhoneOffset = 1000000,
    [int]$WarmupUsers = 50,
    [int]$DrainTimeoutSeconds = 180,
    [switch]$KeepEnvironment
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectNamePrefix = if ($ProjectName) {
    $ProjectName
} else {
    "local-life-seckill-load"
}
if ($projectNamePrefix -notmatch '^[a-z0-9][a-z0-9_-]*$' -or
        $projectNamePrefix.Length -gt 32) {
    throw "ProjectName must be at most 32 lowercase letters, numbers, underscores, or hyphens"
}
$runId = [guid]::NewGuid().ToString("N").Substring(0, 12)
$ProjectName = "$projectNamePrefix-$PID-$runId"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$ComposePrefix = @(
    "compose",
    "-p", $ProjectName,
    "-f", (Join-Path $RepoRoot "docker-compose.yml"),
    "-f", (Join-Path $RepoRoot "load-tests/docker-compose.load.yml")
)
$PerformanceDir = Join-Path $RepoRoot "docs/performance"
$RawResultDir = Join-Path $PerformanceDir "raw"
$ReportPath = Join-Path $PerformanceDir "seckill-load-test.md"
$RunResultDir = Join-Path $PerformanceDir ".seckill-load-run-$runId"
$RunReportPath = Join-Path $PerformanceDir ".seckill-load-report-$runId.md"
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $repoHashBytes = $sha256.ComputeHash(
        [System.Text.Encoding]::UTF8.GetBytes($RepoRoot.ToLowerInvariant()))
} finally {
    $sha256.Dispose()
}
[byte[]]$shortRepoHash = $repoHashBytes[0..7]
$repoHash = [System.BitConverter]::ToString($shortRepoHash).Replace("-", "")
$PublishMutexName = "Local\LocalLifeSeckillLoad-$repoHash"
$MySqlRootPassword = "load_test_root_password"
$MySqlDatabase = "sky_take_out"
$RedisPassword = "load_test_redis_password"

function Invoke-Compose {
    param([string[]]$CommandArgs)

    & docker @ComposePrefix @CommandArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed: $($CommandArgs -join ' ')"
    }
}

function Remove-SafeRunDirectory {
    param([string]$Path)

    $performanceFullPath = [System.IO.Path]::GetFullPath($PerformanceDir).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar)
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $expectedPrefix = $performanceFullPath + [System.IO.Path]::DirectorySeparatorChar
    $leafName = Split-Path -Leaf $fullPath
    if (-not $fullPath.StartsWith(
            $expectedPrefix, [System.StringComparison]::OrdinalIgnoreCase) -or
            $leafName -notmatch '^\.seckill-load-(run|backup)-[a-f0-9]{12}$') {
        throw "Refusing to recursively remove unexpected path: $fullPath"
    }
    if (Test-Path -LiteralPath $fullPath) {
        Remove-Item -LiteralPath $fullPath -Recurse -Force
    }
}

function Publish-ResultsLocked {
    $backupResultDir = Join-Path $PerformanceDir ".seckill-load-backup-$runId"
    if (Test-Path -LiteralPath $backupResultDir) {
        throw "Result backup path already exists: $backupResultDir"
    }

    $hadPreviousResults = Test-Path -LiteralPath $RawResultDir
    if ($hadPreviousResults) {
        Move-Item -LiteralPath $RawResultDir -Destination $backupResultDir
    }

    try {
        Move-Item -LiteralPath $RunResultDir -Destination $RawResultDir
        Move-Item -LiteralPath $RunReportPath -Destination $ReportPath -Force
    } catch {
        $publishError = $_
        try {
            if (Test-Path -LiteralPath $RawResultDir) {
                Move-Item -LiteralPath $RawResultDir -Destination $RunResultDir
            }
            if ($hadPreviousResults -and (Test-Path -LiteralPath $backupResultDir)) {
                Move-Item -LiteralPath $backupResultDir -Destination $RawResultDir
            }
        } catch {
            Write-Warning "Failed to roll back result publication: $($_.Exception.Message)"
        }
        throw $publishError
    }

    if ($hadPreviousResults) {
        try {
            Remove-SafeRunDirectory -Path $backupResultDir
        } catch {
            Write-Warning "Published results but could not remove backup: $($_.Exception.Message)"
        }
    }
}

function Publish-Results {
    $publishMutex = [System.Threading.Mutex]::new($false, $PublishMutexName)
    $lockTaken = $false
    try {
        try {
            $lockTaken = $publishMutex.WaitOne([TimeSpan]::FromMinutes(1))
        } catch [System.Threading.AbandonedMutexException] {
            $lockTaken = $true
        }
        if (-not $lockTaken) {
            throw "Timed out waiting to publish load-test results"
        }
        Publish-ResultsLocked
    } finally {
        if ($lockTaken) {
            $publishMutex.ReleaseMutex()
        }
        $publishMutex.Dispose()
    }
}

function Invoke-MySqlScalar {
    param([string]$Sql)

    $output = & docker @ComposePrefix exec -T mysql mysql `
        --batch --skip-column-names `
        -uroot "-p$MySqlRootPassword" $MySqlDatabase `
        -e $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL query failed: $Sql"
    }
    return (($output | Out-String).Trim())
}

function Invoke-MySql {
    param([string]$Sql)

    & docker @ComposePrefix exec -T mysql mysql `
        -uroot "-p$MySqlRootPassword" $MySqlDatabase `
        -e $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL statement failed: $Sql"
    }
}

function Invoke-RedisScalar {
    param([string[]]$CommandArgs)

    $output = & docker @ComposePrefix exec -T redis redis-cli `
        --no-auth-warning -a $RedisPassword -n 10 @CommandArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Redis command failed: $($CommandArgs -join ' ')"
    }
    return (($output | Out-String).Trim())
}

function Get-PendingCount {
    $pending = Invoke-RedisScalar @("XPENDING", "stream.orders", "g1")
    if (-not $pending) {
        return 0
    }
    return [int](($pending -split "`r?`n")[0])
}

function Get-StreamLag {
    $groupsJson = Invoke-RedisScalar @("--json", "XINFO", "GROUPS", "stream.orders")
    $groups = @($groupsJson | ConvertFrom-Json)
    $group = @($groups | Where-Object { $_.name -eq "g1" }) | Select-Object -First 1
    if ($null -eq $group) {
        throw "Redis Stream consumer group g1 was not found"
    }
    return [int]$group.lag
}

function Get-AppPublishedPort {
    $output = & docker @ComposePrefix port app 8080
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to resolve the published application port"
    }
    $line = (@($output) | Select-Object -First 1 | Out-String).Trim()
    if ($line -notmatch ':(\d+)$') {
        throw "Unexpected docker compose port output: $line"
    }
    return [int]$Matches[1]
}

function Wait-AppReady {
    param([int]$PublishedPort)

    $deadline = (Get-Date).AddMinutes(3)
    $url = "http://localhost:$PublishedPort/user/store-type/list"
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Method Get -Uri $url -TimeoutSec 5
            if ($response.code -eq 1) {
                return
            }
        } catch {
            Start-Sleep -Seconds 2
            continue
        }
        Start-Sleep -Seconds 2
    }
    throw "Application did not become ready: $url"
}

function Invoke-K6Scenario {
    param(
        [int]$VirtualUsers,
        [int]$VoucherId,
        [string]$SummaryPath
    )

    Invoke-Compose @(
        "run", "--rm", "--no-deps",
        "-e", "BASE_URL=http://app:8080",
        "-e", "VUS=$VirtualUsers",
        "-e", "VOUCHER_ID=$VoucherId",
        "-e", "PHONE_OFFSET=$PhoneOffset",
        "-e", "LOGIN_CODE=123456",
        "-e", "SUMMARY_PATH=$SummaryPath",
        "k6", "run", "/scripts/seckill.js"
    )
}

function Prepare-Scenario {
    param(
        [int]$VoucherId,
        [int]$Stock
    )

    if ((Get-PendingCount) -ne 0) {
        throw "Cannot start scenario while stream.orders has pending messages"
    }

    $sql = @"
DELETE FROM tb_voucher_order WHERE voucher_id = $VoucherId;
INSERT INTO tb_voucher
    (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES
    ($VoucherId, 2, 'Load Test Voucher $VoucherId', 'Load test only', 'One per user', 3000, 990, 1, 1)
ON DUPLICATE KEY UPDATE
    title = VALUES(title), status = 1, update_time = NOW();
INSERT INTO tb_seckill_voucher
    (voucher_id, stock, begin_time, end_time)
VALUES
    ($VoucherId, $Stock, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY))
ON DUPLICATE KEY UPDATE
    stock = VALUES(stock), begin_time = VALUES(begin_time), end_time = VALUES(end_time), update_time = NOW();
"@
    Invoke-MySql $sql
    [void](Invoke-RedisScalar @(
        "DEL",
        "seckill:stock:$VoucherId",
        "seckill:order:$VoucherId"
    ))
}

function Wait-ScenarioDrained {
    param(
        [int]$VoucherId,
        [int]$ExpectedOrders
    )

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $deadline = (Get-Date).AddSeconds($DrainTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $orders = [int](Invoke-MySqlScalar `
            "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = $VoucherId")
        $pending = Get-PendingCount
        $lag = Get-StreamLag
        if ($orders -eq $ExpectedOrders -and $pending -eq 0 -and $lag -eq 0) {
            $stopwatch.Stop()
            return $stopwatch.Elapsed.TotalSeconds
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Scenario $VoucherId did not drain within $DrainTimeoutSeconds seconds"
}

function Assert-ScenarioConsistency {
    param(
        [int]$VoucherId,
        [int]$InitialStock,
        [int]$ConcurrentUsers
    )

    $orders = [int](Invoke-MySqlScalar `
        "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = $VoucherId")
    $distinctUsers = [int](Invoke-MySqlScalar `
        "SELECT COUNT(DISTINCT user_id) FROM tb_voucher_order WHERE voucher_id = $VoucherId")
    $databaseStock = [int](Invoke-MySqlScalar `
        "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = $VoucherId")
    $redisStock = [int](Invoke-RedisScalar @("GET", "seckill:stock:$VoucherId"))
    $redisBuyers = [int](Invoke-RedisScalar @("SCARD", "seckill:order:$VoucherId"))
    $databaseBuyerIds = @((Invoke-MySqlScalar `
        "SELECT CAST(user_id AS CHAR) FROM tb_voucher_order WHERE voucher_id = $VoucherId") `
        -split "`r?`n" | Where-Object { $_ } | Sort-Object)
    $redisBuyerIds = @((Invoke-RedisScalar @("SMEMBERS", "seckill:order:$VoucherId")) `
        -split "`r?`n" | Where-Object { $_ } | Sort-Object)
    $buyerIdDifferences = @(Compare-Object `
        -ReferenceObject $databaseBuyerIds -DifferenceObject $redisBuyerIds)
    $pending = Get-PendingCount
    $lag = Get-StreamLag
    $deadLetters = [int](Invoke-RedisScalar @("XLEN", "stream.orders.dlq"))

    $checks = [ordered]@{
        orders_match_stock = $orders -eq $InitialStock
        no_oversell = $orders -le $InitialStock
        no_duplicate_users = $distinctUsers -eq $orders
        database_stock_zero = $databaseStock -eq 0
        redis_stock_zero = $redisStock -eq 0
        inventory_conserved = ($databaseStock + $orders) -eq $InitialStock
        redis_buyers_match = $redisBuyers -eq $orders
        redis_buyer_ids_match = $buyerIdDifferences.Count -eq 0
        stream_pending_zero = $pending -eq 0
        stream_lag_zero = $lag -eq 0
        dead_letter_zero = $deadLetters -eq 0
    }
    $failedChecks = @($checks.GetEnumerator() | Where-Object { -not $_.Value })
    if ($failedChecks.Count -gt 0) {
        throw "Scenario consistency failed: $($failedChecks.Name -join ', ')"
    }

    return [pscustomobject]@{
        Orders = $orders
        DistinctUsers = $distinctUsers
        DatabaseStock = $databaseStock
        RedisStock = $redisStock
        RedisBuyers = $redisBuyers
        Pending = $pending
        Lag = $lag
        DeadLetters = $deadLetters
        Rejected = $ConcurrentUsers - $orders
    }
}

function Read-K6Metric {
    param(
        [object]$Summary,
        [string]$Metric,
        [string]$Value
    )

    return [double]$Summary.metrics.$Metric.$Value
}

function Write-Report {
    param(
        [object[]]$Results,
        [string]$OutputPath
    )

    $processor = Get-CimInstance Win32_Processor | Select-Object -First 1
    $computer = Get-CimInstance Win32_ComputerSystem
    $os = Get-CimInstance Win32_OperatingSystem
    $dockerVersion = (& docker version --format '{{.Server.Version}}' | Out-String).Trim()
    $rows = foreach ($result in $Results) {
        "| $($result.Concurrency) | $($result.InitialStock) | $($result.Accepted) | $($result.OutOfStock) | $($result.RequestsPerSecond) | $($result.AverageMs) | $($result.P95Ms) | $($result.P99Ms) | $($result.MaxMs) | $($result.DrainSeconds) | PASS |"
    }

    $report = @"
# Seckill Load Test Report

Generated: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss K")

## Scope

This benchmark measures the authenticated HTTP request that atomically reserves Redis stock and publishes a Redis Stream message. Seckill req/s is calculated over the burst window from the first request start to the final response completion, excluding login setup. Database persistence is asynchronous, so drain time and cross-store consistency are reported separately.

## Environment

- Host OS: Windows $($os.Version)
- CPU: $($processor.Name) ($($processor.NumberOfCores) cores / $($processor.NumberOfLogicalProcessors) logical processors)
- Memory: $([math]::Round($computer.TotalPhysicalMemory / 1GB, 1)) GB
- Docker Engine: $dockerVersion
- Application runtime: Eclipse Temurin 17 container
- MySQL: 8.0 container
- Redis: 7 Alpine container
- k6: 0.54.0 container
- Topology: all services run on one Docker Desktop host; results are not production capacity claims
- Warm-up: $WarmupUsers virtual users on a separate voucher; excluded from the table

## Results

| Concurrent users | Initial stock | Accepted | Sold out | Seckill req/s | Avg (ms) | P95 (ms) | P99 (ms) | Max (ms) | Async drain (s) | Consistency |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | :---: |
$($rows -join "`n")

## Consistency Checks

Every scenario sends approximately twice as many concurrent users as available stock and must satisfy all of these invariants:

- accepted requests = initial stock; remaining requests are classified as sold out
- database orders = distinct purchasing users = initial stock
- MySQL stock = Redis stock = 0 after the stream drains
- Redis buyer set exactly matches the database purchasing-user set
- Redis Stream pending count = 0 and consumer-group lag = 0
- dead-letter stream length = 0

## Reproduce

    powershell -ExecutionPolicy Bypass -File .\load-tests\run-seckill-load-test.ps1

Raw k6 summaries are stored in docs/performance/raw/.

## Interpretation

The request latency measures Redis Lua admission and Stream publishing, not synchronous MySQL insertion. Async drain is the additional time from the end of the k6 burst until every accepted order is persisted and acknowledged. Results are local single-machine measurements and should be compared only on equivalent hardware and Docker settings.
"@
    Set-Content -LiteralPath $OutputPath -Value $report -Encoding utf8
}

if ($Concurrency.Count -eq 0 -or @($Concurrency | Where-Object { $_ -le 0 }).Count -gt 0) {
    throw "Concurrency values must be positive integers"
}
if (@($Concurrency | Sort-Object -Unique).Count -ne $Concurrency.Count) {
    throw "Concurrency values must be unique"
}
if ($WarmupUsers -le 0) {
    throw "WarmupUsers must be a positive integer"
}
if ($VoucherIdBase -le 1) {
    throw "VoucherIdBase must be greater than 1"
}

$environmentValues = [ordered]@{
    APP_PORT = [string]$AppPort
    MANAGEMENT_PORT = [string]$ManagementPort
    MYSQL_PORT = [string]$MySqlPort
    REDIS_PORT = [string]$RedisPort
    MYSQL_ROOT_PASSWORD = $MySqlRootPassword
    MYSQL_DATABASE = $MySqlDatabase
    MYSQL_USER = "sky"
    MYSQL_PASSWORD = "load_test_app_password"
    REDIS_PASSWORD = $RedisPassword
    SKY_JWT_ADMIN_SECRET = "load-test-admin-secret-at-least-32-characters"
    SKY_JWT_USER_SECRET = "load-test-user-secret-at-least-32-characters"
    SKY_AUTH_FIXED_LOGIN_CODE = "123456"
    SKY_SECKILL_CONSUMER_NAME = "load-test-consumer"
    K6_RESULTS_DIR = "./docs/performance/.seckill-load-run-$runId"
}
$previousEnvironment = @{}
foreach ($name in $environmentValues.Keys) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable(
        $name, [EnvironmentVariableTarget]::Process)
}
$results = @()
$composeStarted = $false
$runError = $null
try {
    New-Item -ItemType Directory -Force -Path $PerformanceDir | Out-Null
    New-Item -ItemType Directory -Path $RunResultDir | Out-Null
    foreach ($name in $environmentValues.Keys) {
        [Environment]::SetEnvironmentVariable(
            $name, $environmentValues[$name], [EnvironmentVariableTarget]::Process)
    }

    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker is not available. Start Docker Desktop and retry."
    }

    Write-Host "Compose project: $ProjectName" -ForegroundColor DarkGray
    $composeStarted = $true
    Invoke-Compose @("up", "-d", "--build", "mysql", "redis", "app")
    $publishedAppPort = Get-AppPublishedPort
    Wait-AppReady -PublishedPort $publishedAppPort
    [void](Invoke-RedisScalar @("DEL", "stream.orders.dlq"))

    $warmupVoucherId = $VoucherIdBase - 1
    $warmupStock = [int][math]::Ceiling($WarmupUsers / 2)
    Write-Host "`nWarming up: concurrency=$WarmupUsers voucher=$warmupVoucherId" -ForegroundColor Cyan
    Prepare-Scenario -VoucherId $warmupVoucherId -Stock $warmupStock
    Invoke-K6Scenario -VirtualUsers $WarmupUsers `
        -VoucherId $warmupVoucherId -SummaryPath "/tmp/k6-warmup.json"
    [void](Wait-ScenarioDrained `
        -VoucherId $warmupVoucherId -ExpectedOrders $warmupStock)
    [void](Assert-ScenarioConsistency `
        -VoucherId $warmupVoucherId -InitialStock $warmupStock `
        -ConcurrentUsers $WarmupUsers)

    for ($index = 0; $index -lt $Concurrency.Count; $index++) {
        $vus = $Concurrency[$index]
        $initialStock = [int][math]::Ceiling($vus / 2)
        $voucherId = $VoucherIdBase + $index
        Prepare-Scenario -VoucherId $voucherId -Stock $initialStock

        $summaryName = "k6-$vus.json"
        $summaryPath = Join-Path $RunResultDir $summaryName

        Write-Host "`nRunning seckill load test: concurrency=$vus voucher=$voucherId" -ForegroundColor Cyan
        Invoke-K6Scenario -VirtualUsers $vus -VoucherId $voucherId `
            -SummaryPath "/results/$summaryName"

        $drainSeconds = Wait-ScenarioDrained `
            -VoucherId $voucherId -ExpectedOrders $initialStock
        $consistency = Assert-ScenarioConsistency `
            -VoucherId $voucherId -InitialStock $initialStock -ConcurrentUsers $vus
        $summary = Get-Content -Raw $summaryPath | ConvertFrom-Json
        $accepted = [int](Read-K6Metric $summary "seckill_accepted" "count")
        $outOfStock = [int](Read-K6Metric $summary "seckill_out_of_stock" "count")
        $unexpected = [int](Read-K6Metric $summary "seckill_unexpected" "count")
        if ($accepted -ne $initialStock -or
                $outOfStock -ne ($vus - $initialStock) -or
                $unexpected -ne 0) {
            throw "Unexpected k6 classification: accepted=$accepted outOfStock=$outOfStock unexpected=$unexpected"
        }
        $burstStartMs = Read-K6Metric $summary "seckill_request_started_at_ms" "min"
        $burstEndMs = Read-K6Metric $summary "seckill_request_completed_at_ms" "max"
        $burstDurationSeconds = ($burstEndMs - $burstStartMs) / 1000
        if ($burstDurationSeconds -le 0) {
            throw "Invalid seckill burst window: start=$burstStartMs end=$burstEndMs"
        }
        $requestsPerSecond = [double]$summary.derived.seckillRequestsPerSecond
        $calculatedRequestsPerSecond = ($accepted + $outOfStock) / $burstDurationSeconds
        if ([math]::Abs($requestsPerSecond - $calculatedRequestsPerSecond) -gt 0.001) {
            throw "k6 burst throughput does not match its raw metrics"
        }
        $results += [pscustomobject]@{
            Concurrency = $vus
            InitialStock = $initialStock
            Accepted = $accepted
            OutOfStock = $outOfStock
            RequestsPerSecond = [math]::Round($requestsPerSecond, 2)
            AverageMs = [math]::Round((Read-K6Metric $summary "seckill_request_duration" "avg"), 2)
            P95Ms = [math]::Round((Read-K6Metric $summary "seckill_request_duration" "p(95)"), 2)
            P99Ms = [math]::Round((Read-K6Metric $summary "seckill_request_duration" "p(99)"), 2)
            MaxMs = [math]::Round((Read-K6Metric $summary "seckill_request_duration" "max"), 2)
            DrainSeconds = [math]::Round($drainSeconds, 2)
            Orders = $consistency.Orders
        }
    }

    Write-Report -Results $results -OutputPath $RunReportPath
    Publish-Results
    Write-Host "`nLoad test passed. Report: $ReportPath" -ForegroundColor Green
} catch {
    $runError = $_
} finally {
    if ($composeStarted -and -not $KeepEnvironment) {
        try {
            Invoke-Compose @("down", "-v", "--remove-orphans")
        } catch {
            if ($null -eq $runError) {
                $runError = $_
            } else {
                Write-Warning "Environment cleanup also failed: $($_.Exception.Message)"
            }
        }
    }

    if (-not $KeepEnvironment) {
        try {
            Remove-SafeRunDirectory -Path $RunResultDir
            if (Test-Path -LiteralPath $RunReportPath) {
                Remove-Item -LiteralPath $RunReportPath -Force
            }
        } catch {
            if ($null -eq $runError) {
                $runError = $_
            } else {
                Write-Warning "Temporary-result cleanup also failed: $($_.Exception.Message)"
            }
        }
    } elseif (Test-Path -LiteralPath $RunResultDir) {
        Write-Warning "Temporary results retained for debugging: $RunResultDir"
    }

    foreach ($name in $environmentValues.Keys) {
        [Environment]::SetEnvironmentVariable(
            $name, $previousEnvironment[$name], [EnvironmentVariableTarget]::Process)
    }
}

if ($null -ne $runError) {
    throw $runError
}
