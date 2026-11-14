$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$publicBuild = Join-Path $projectRoot 'frontend/frontend'
$adminBuild = Join-Path $projectRoot 'admin/dist'
$htmlTarget = Join-Path $PSScriptRoot 'html'
$migrationSource = Join-Path $projectRoot 'backend/sql/server-migration'
$deploymentSqlFiles = @('01-init.sql', '02-upgrade.sql')

foreach ($requiredFile in @((Join-Path $publicBuild 'index.html'), (Join-Path $adminBuild 'index.html'))) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw 'Build frontend and admin successfully before preparing deployment files.'
    }
}
foreach ($migration in $deploymentSqlFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $migrationSource $migration) -PathType Leaf)) {
        throw "Required deployment SQL is missing: $migration"
    }
}
foreach ($folder in @('html/admin', 'certs', 'secrets', 'data/uploads', 'logs/backend', 'logs/nginx', 'sql')) {
    New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot $folder) -Force | Out-Null
}

# Publish static files only; exclude Node sources, scripts, and runtime settings.
foreach ($file in @('index.html', 'robots.txt', 'sitemap.xml')) {
    $source = Join-Path $publicBuild $file
    if (Test-Path -LiteralPath $source -PathType Leaf) {
        Copy-Item -LiteralPath $source -Destination (Join-Path $htmlTarget $file) -Force
    }
}
Copy-Item -LiteralPath (Join-Path $publicBuild 'assets') -Destination $htmlTarget -Recurse -Force
Copy-Item -LiteralPath (Join-Path $adminBuild 'index.html') -Destination (Join-Path $htmlTarget 'admin/index.html') -Force
Copy-Item -LiteralPath (Join-Path $adminBuild 'assets') -Destination (Join-Path $htmlTarget 'admin') -Recurse -Force

# Schema upgrades are deployment files, never public web assets; execution is manual.
foreach ($migration in $deploymentSqlFiles) {
    Copy-Item -LiteralPath (Join-Path $migrationSource $migration) -Destination (Join-Path $PSScriptRoot "sql/$migration") -Force
}

$existingUploads = Join-Path $projectRoot 'data/uploads'
if (Test-Path -LiteralPath $existingUploads -PathType Container) {
    Copy-Item -LiteralPath $existingUploads -Destination (Join-Path $PSScriptRoot 'data') -Recurse -Force
}
Write-Output "Static site and upload files are ready: $htmlTarget"
Write-Output 'Deployment SQL is ready in sql/: choose 01-init.sql for an empty database OR 02-upgrade.sql for an existing database.'
Write-Output 'Configure passwords, SMS credentials, and SSL certificates on the server.'
