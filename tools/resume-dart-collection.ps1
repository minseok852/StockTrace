param([switch]$CheckList, [string]$ResponseFile)

$ErrorActionPreference = 'Stop'

function Get-DartResponse($uri, $destination) {
    $options = @{}
    if ($destination) { $options.OutFile = $destination }
    try {
        Invoke-WebRequest -UseBasicParsing -MaximumRedirection 0 -TimeoutSec 45 -ErrorAction Stop -Verbose:$false -Debug:$false -Uri $uri @options
    } catch {
        $status = $_.Exception.Status
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        throw "OpenDART request failed (Windows HTTP: $status, $($_.Exception.GetBaseException().GetType().Name))."
    }
}

$root = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $root 'backend')
try {
    $key = $env:OPENDART_API_KEY
    if (-not $key) {
        $entry = Get-Content (Join-Path $root '.env') -Encoding UTF8 |
            Where-Object { $_ -match '^\s*OPENDART_API_KEY\s*=' } | Select-Object -First 1
        if ($entry) { $key = ($entry -split '=', 2)[1].Trim().Trim([char]34, [char]39) }
    }
    if ($key -notmatch '^[a-zA-Z0-9]{40}$') { throw 'Check OPENDART_API_KEY in the project .env.' }
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $ProgressPreference = 'SilentlyContinue'
    if ($ResponseFile) {
        while ($null -ne ($request = [Console]::ReadLine())) {
            if ($request -notmatch '^(corpCode\.xml|list\.json|document\.xml)\?[a-zA-Z0-9_=&]*$') {
                throw 'Invalid OpenDART request.'
            }
            try {
                Get-DartResponse ('https://opendart.fss.or.kr/api/' + $request + '&crtfc_key=' + $key) $ResponseFile
                Write-Output 'OK'
            } catch {
                Write-Output $_.Exception.Message
            }
        }
        return
    }
    if ($CheckList) {
        # This filing day already has a verified D002 archive in the project.
        $url = 'https://opendart.fss.or.kr/api/list.json?bgn_de=20260922&end_de=20260922&pblntf_detail_ty=D002&page_no=1&page_count=1&crtfc_key=' + $key
        $response = Get-DartResponse $url
        $body = $response.Content
        if ($body -is [byte[]]) { $body = [Text.Encoding]::UTF8.GetString($body) }
        try { $json = ConvertFrom-Json -InputObject $body -ErrorAction Stop }
        catch { throw "HTTP $($response.StatusCode), expected OpenDART JSON." }
        if ($json.status -notmatch '^[0-9]{3}$') { throw "HTTP $($response.StatusCode), missing OpenDART JSON status." }
        $summary = "list.json HTTP $($response.StatusCode), OpenDART $($json.status)"
        if ($json.status -eq '013') { Write-Output "$summary (no filings returned)"; return }
        if ($json.status -ne '000') { throw $summary }
        $receipt = @($json.list)[0].rcept_no
        if ($receipt -notmatch '^[0-9]{14}$') { throw "$summary (missing receipt number)." }
        Write-Output "$summary, receipt $receipt"
        return
    }
    Write-Output 'Collecting OpenDART filings via Windows HTTP.'
    & (Join-Path $root 'gradlew.bat') -p . run --args=collect-windows
    $result = $LASTEXITCODE
} finally { Pop-Location }
exit $result
