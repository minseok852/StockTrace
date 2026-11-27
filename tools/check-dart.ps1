param([switch]$UseSavedZip, [switch]$All)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$directory = Join-Path $root 'build/dart-check'
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not $UseSavedZip) {
    $entries = @(Get-Content -LiteralPath (Join-Path $root '.env') -Encoding UTF8 |
        Where-Object { $_ -match '^\s*OPENDART_API_KEY\s*=' })
    if ($entries.Count -ne 1) { throw 'Expected one OPENDART_API_KEY entry in .env.' }
    $key = ($entries[0] -split '=', 2)[1].Trim().Trim([char]34, [char]39)
    if ($key -notmatch '^[0-9A-Za-z]{40}$') { throw 'Check the 40-character key in .env.' }
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $null = New-Item -ItemType Directory -Path $directory -Force
}

function Get-ReportZip($receipt, $zipPath) {
    $tempPath = Join-Path $directory ([guid]::NewGuid().ToString() + '.tmp')
    try {
        $uri = 'https://opendart.fss.or.kr/api/document.xml?crtfc_key=' + $key + '&rcept_no=' + $receipt
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $uri -MaximumRedirection 0 -TimeoutSec 30 -OutFile $tempPath
        } catch {
            $detail = $_.Exception.GetBaseException().GetType().Name
            throw "OpenDART request failed ($detail). Check connectivity to opendart.fss.or.kr."
        }
        try {
            $testArchive = [IO.Compression.ZipFile]::OpenRead($tempPath)
            $testArchive.Dispose()
        } catch {
            $body = [IO.File]::ReadAllText($tempPath)
            if ($body -match '<status>(\d{3})</status>') { throw "OpenDART status $($Matches[1]); no ZIP returned." }
            throw 'OpenDART returned a non-ZIP response.'
        }
        Move-Item -LiteralPath $tempPath -Destination $zipPath -Force
    } finally {
        if (Test-Path -LiteralPath $tempPath) { Remove-Item -LiteralPath $tempPath }
    }
}

function Get-ChoReceipt {
    $cache = Join-Path $directory 'cho-20260317.txt'
    if (Test-Path -LiteralPath $cache) {
        $saved = (Get-Content -LiteralPath $cache -Raw).Trim()
        if ($saved -match '^20260317\d{6}$') { return $saved }
    }
    if ($UseSavedZip) { throw 'Correction receipt not cached.' }
    $uri = 'https://opendart.fss.or.kr/api/list.json?corp_code=00126380&bgn_de=20260317&end_de=20260317&pblntf_detail_ty=D002&page_count=100&crtfc_key=' + $key
    try { $response = Invoke-RestMethod -Uri $uri -MaximumRedirection 0 -TimeoutSec 30 } catch { throw 'OpenDART list request failed.' }
    if ($response.status -ne '000') { throw "OpenDART list status $($response.status)." }
    $found = @($response.list | Where-Object { ($_.flr_nm -replace '\s','') -eq '조영준' -and $_.report_nm -match '정정' })
    if ($found.Count -ne 1 -or $found[0].rcept_no -notmatch '^20260317\d{6}$') { throw 'Cannot identify a unique corrected report for Cho.' }
    Set-Content -LiteralPath $cache -Encoding ascii -Value $found[0].rcept_no
    return $found[0].rcept_no
}

function Read-TradeRows($zipPath, $receipt) {
    $archive = [IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $file = $archive.GetEntry("$receipt.xml")
        if (-not $file) { throw 'Receipt XML missing in ZIP.' }
        $stream = $file.Open()
        try {
            $xml = New-Object Xml.XmlDocument
            $xml.XmlResolver = $null
            $xml.Load($stream)
        } finally { $stream.Dispose() }
        $rows = $xml.SelectNodes('//BODY//TABLE-GROUP[@ACLASS="RPT_RSN"]//TR[*[@AUNIT="RPT_RSN"]]')
        if ($rows.Count -eq 0) { throw 'No structured trade rows found in report body.' }
        foreach ($row in $rows) {
            $fields = @{}
            foreach ($cell in $row.ChildNodes) {
                if ($cell.NodeType -ne 'Element') { continue }
                $id = $cell.GetAttribute('AUNIT') + $cell.GetAttribute('ACODE')
                $fields[$id] = $cell.InnerText -replace '\s',''
            }
            if ($fields.STR_KND -ne '보통주') { continue }
            $price = $fields.ACI_AMT2 -replace '[,\s]','' -replace '\(원\)|원|\(\)$',''
            [pscustomobject]@{Date=$fields.MDF_DM -replace '\D',''; Reason=$fields.RPT_RSN;
                Quantity=$fields.MDF_STK_CNT -replace '[,+]',''; Price=$price; Note=$fields.RMK}
        }
    } finally { $archive.Dispose() }
}

$samples = Import-Csv -LiteralPath (Join-Path $PSScriptRoot 'dart-samples.csv') -Encoding UTF8
if (-not $All) { $samples = $samples | Where-Object Receipt -eq '20260731000755' }
$results = foreach ($group in ($samples | Group-Object Receipt)) {
    $receipt = $group.Name
    $status = 'UNAVAILABLE'
    $hits = 0
    $detail = ''
    try {
        if ($receipt -eq 'CHO-CORRECTION') { $receipt = Get-ChoReceipt }
        $zipPath = Join-Path $directory "$receipt.zip"
        if (-not (Test-Path -LiteralPath $zipPath)) {
            if ($UseSavedZip) { throw 'ZIP not cached.' }
            Get-ReportZip $receipt $zipPath
        }
        $status = 'PARSE_ERROR'
        $actual = @(Read-TradeRows $zipPath $receipt)
        $missing = foreach ($expected in $group.Group) {
            $same = @($actual | Where-Object {
                $_.Date -eq $expected.Date -and $_.Reason -eq $expected.Reason -and
                $_.Quantity -eq $expected.Quantity -and $_.Price -eq $expected.Price -and
                (!$expected.Note -or $_.Note -like "*$($expected.Note)*")
            })
            if ($same.Count -gt 0) { $hits++ } else { "$($expected.Date) $($expected.Reason) $($expected.Quantity) @ $($expected.Price)" }
        }
        $status = if ($hits -eq $group.Count) { 'MATCH' } else { 'MISMATCH' }
        $detail = $missing -join ' / '
    } catch { $detail = $_.Exception.Message }
    [pscustomobject]@{Label=$group.Group[0].Label; Receipt=$receipt; Status=$status; Matched=$hits; Expected=$group.Count; Detail=$detail}
}
$results | Format-Table -AutoSize -Wrap
if ($All -and (Test-Path -LiteralPath $directory)) {
    $results | Export-Csv -LiteralPath (Join-Path $directory 'results.csv') -Encoding UTF8 -NoTypeInformation
}
$passed = @($results | Where-Object Status -eq 'MATCH').Count
Write-Output "MATCH $passed / $(@($results).Count) reports"
if ($passed -ne @($results).Count) { exit 1 }
