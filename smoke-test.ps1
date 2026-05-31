param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Phone = "",
    [string]$Code = "",
    [int]$StoreId = 1,
    [int]$StoreTypeId = 1,
    [int]$VoucherShopId = 1,
    [int]$SeckillVoucherId = 2,
    [switch]$SkipSeckill
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function New-TestPhone {
    return ("138{0:D8}" -f (Get-Random -Minimum 0 -Maximum 100000000))
}

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-Warn([string]$Message) {
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Get-ApiUrl([string]$Path) {
    if ($Path.StartsWith("http")) {
        return $Path
    }
    return "{0}{1}" -f $BaseUrl.TrimEnd("/"), $Path
}

function Invoke-AppApi {
    param(
        [string]$Method = "GET",
        [string]$Path,
        [object]$Body,
        [hashtable]$Headers
    )

    $uri = Get-ApiUrl $Path
    $invokeParams = @{
        Method    = $Method
        Uri       = $uri
        TimeoutSec = 30
    }

    if ($Headers) {
        $invokeParams.Headers = $Headers
    }

    if ($PSBoundParameters.ContainsKey("Body")) {
        $invokeParams.ContentType = "application/json"
        $invokeParams.Body = ($Body | ConvertTo-Json -Depth 10)
    }

    try {
        return Invoke-RestMethod @invokeParams
    } catch {
        $response = $_.Exception.Response
        if ($response -and $response.GetResponseStream()) {
            $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
            $content = $reader.ReadToEnd()
            throw "Request failed: $Method $uri`n$content"
        }
        throw
    }
}

function Assert-Success {
    param(
        [object]$Response,
        [string]$Name
    )

    if ($null -eq $Response) {
        throw "$Name returned no response"
    }
    if ($Response.code -ne 1) {
        throw "$Name failed: $($Response.msg)"
    }
    Write-Ok $Name
    return $Response.data
}

function As-Array {
    param([object]$Value)
    if ($null -eq $Value) {
        return @()
    }
    return @($Value)
}

if (-not $Phone) {
    $Phone = New-TestPhone
}

Write-Host "Smoke test base URL: $BaseUrl"
Write-Host "Smoke test phone   : $Phone"

Write-Step "Checking Swagger page"
$doc = Invoke-WebRequest -Uri (Get-ApiUrl "/doc.html") -TimeoutSec 20
if ($doc.StatusCode -lt 200 -or $doc.StatusCode -ge 300) {
    throw "Swagger page is not reachable"
}
Write-Ok "Swagger page reachable"

Write-Step "Sending login code"
$sendCodeResponse = Invoke-AppApi -Method POST -Path "/user/user/code?phone=$Phone"
[void](Assert-Success -Response $sendCodeResponse -Name "Send login code")

if (-not $Code) {
    Write-Host "Read the 6-digit verification code from the application logs." -ForegroundColor Yellow
    $Code = Read-Host "Enter verification code"
}
if (-not $Code) {
    throw "Verification code is required"
}

Write-Step "Logging in"
$loginResponse = Invoke-AppApi -Method POST -Path "/user/user/login" -Body @{
    phone = $Phone
    code  = $Code
}
$loginData = Assert-Success -Response $loginResponse -Name "User login"
$token = $loginData.token
if (-not $token) {
    throw "Login succeeded but token is empty"
}
$headers = @{ authentication = $token }

Write-Step "Testing public APIs"
$storeTypes = Assert-Success -Response (Invoke-AppApi -Path "/user/store-type/list") -Name "Store type list"
$stores = Assert-Success -Response (Invoke-AppApi -Path "/user/store/of/type?typeId=$StoreTypeId&current=1") -Name "Store list by type"
$storeDetail = Assert-Success -Response (Invoke-AppApi -Path "/user/store/$StoreId") -Name "Store detail"
$voucherList = Assert-Success -Response (Invoke-AppApi -Path "/user/voucher/list/$VoucherShopId") -Name "Voucher list"
$hotBlogs = Assert-Success -Response (Invoke-AppApi -Path "/user/blog/hot?current=1") -Name "Hot blog list"

Write-Step "Testing authenticated user APIs"
$me = Assert-Success -Response (Invoke-AppApi -Headers $headers -Path "/user/user/me") -Name "Current user profile"
[void](Assert-Success -Response (Invoke-AppApi -Method POST -Headers $headers -Path "/user/user/sign") -Name "Daily sign-in")
$signCount = Assert-Success -Response (Invoke-AppApi -Headers $headers -Path "/user/user/sign/count") -Name "Consecutive sign count"

$blogTitle = "SmokeTest Blog $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$createdBlogId = Assert-Success -Response (
    Invoke-AppApi -Method POST -Headers $headers -Path "/user/blog" -Body @{
        shopId  = $StoreId
        title   = $blogTitle
        images  = "https://example.com/smoke-test.jpg"
        content = "This blog was created by smoke-test.ps1"
    }
) -Name "Publish blog"

[void](Assert-Success -Response (Invoke-AppApi -Headers $headers -Path "/user/blog/$createdBlogId") -Name "Query published blog")
[void](Assert-Success -Response (Invoke-AppApi -Method PUT -Headers $headers -Path "/user/blog/like/$createdBlogId") -Name "Like published blog")
[void](Assert-Success -Response (Invoke-AppApi -Headers $headers -Path "/user/blog/likes/$createdBlogId") -Name "Query blog likes")
[void](Assert-Success -Response (Invoke-AppApi -Headers $headers -Path "/user/blog/of/me?current=1") -Name "Query my blogs")
[void](Assert-Success -Response (Invoke-AppApi -Headers $headers -Path "/user/blog/of/follow?lastId=$([DateTimeOffset]::Now.ToUnixTimeMilliseconds())&offset=0") -Name "Query follow feed")

$followTargetId = $null
foreach ($blog in (As-Array $hotBlogs)) {
    if ($null -ne $blog.userId -and [int64]$blog.userId -ne [int64]$me.id) {
        $followTargetId = [int64]$blog.userId
        break
    }
}

if ($null -ne $followTargetId) {
    [void](Assert-Success -Response (Invoke-AppApi -Method PUT -Headers $headers -Path "/user/follow/$followTargetId/true") -Name "Follow user")
    [void](Assert-Success -Response (Invoke-AppApi -Headers $headers -Path "/user/follow/or/not/$followTargetId") -Name "Check follow status")
    [void](Assert-Success -Response (Invoke-AppApi -Headers $headers -Path "/user/follow/common/$followTargetId") -Name "Query common follows")
} else {
    Write-Warn "No suitable follow target found in hot blogs, skipping follow tests"
}

if (-not $SkipSeckill) {
    Write-Step "Testing seckill voucher API"
    $seckillResponse = Invoke-AppApi -Method POST -Headers $headers -Path "/user/voucher-order/seckill/$SeckillVoucherId"
    if ($seckillResponse.code -eq 1) {
        $orderId = $seckillResponse.data
        Write-Ok "Seckill voucher"
        Write-Host "Voucher order id: $orderId"

        $duplicateResponse = Invoke-AppApi -Method POST -Headers $headers -Path "/user/voucher-order/seckill/$SeckillVoucherId"
        if ($duplicateResponse.code -eq 1) {
            Write-Warn "Duplicate seckill unexpectedly succeeded; check one-user-one-order logic"
        } else {
            Write-Ok "Duplicate seckill blocked: $($duplicateResponse.msg)"
        }
    } else {
        Write-Warn "Seckill request did not succeed: $($seckillResponse.msg)"
    }
} else {
    Write-Warn "Skipped seckill test"
}

Write-Step "Smoke test summary"
Write-Host "User ID         : $($me.id)"
Write-Host "Phone           : $Phone"
Write-Host "Created blog ID : $createdBlogId"
Write-Host "Sign count      : $signCount"
Write-Host "Store types     : $((As-Array $storeTypes).Count)"
Write-Host "Stores returned : $((As-Array $stores).Count)"
Write-Host "Vouchers        : $((As-Array $voucherList).Count)"
Write-Host "Hot blogs       : $((As-Array $hotBlogs).Count)"
Write-Ok "Smoke test finished"
