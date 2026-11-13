# ASCII source is intentional: compatible with Windows PowerShell 5.1 and PowerShell 7.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))

function Invoke-BuildCommand {
    param([string]$Tool, [string[]]$CommandArguments)
    $executable = Get-Command $Tool -ErrorAction Stop
    $savedErrorPreference = $ErrorActionPreference
    try {
        # Native tools may print warnings on stderr even when they succeed.
        # Check their exit code explicitly instead of treating stderr as failure.
        $ErrorActionPreference = 'Continue'
        & $executable.Source @CommandArguments
        $commandExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorPreference
    }
    if ($commandExitCode -ne 0) {
        throw "$Tool failed with exit code $commandExitCode. Deployment preparation stopped."
    }
}

Push-Location $projectRoot
try {
    $mavenTool = if (Get-Command mvn.cmd -ErrorAction SilentlyContinue) { 'mvn.cmd' } else { 'mvn' }
    $npmTool = if (Get-Command npm.cmd -ErrorAction SilentlyContinue) { 'npm.cmd' } else { 'npm' }
    Invoke-BuildCommand -Tool $mavenTool -CommandArguments @('-f', 'backend/pom.xml', '-Dmaven.test.redirectTestOutputToFile=true', 'package')
    Invoke-BuildCommand -Tool $npmTool -CommandArguments @('--prefix', 'frontend', 'run', 'build')
    Invoke-BuildCommand -Tool $npmTool -CommandArguments @('--prefix', 'admin', 'run', 'build')
    & (Join-Path $PSScriptRoot 'prepare-static.ps1')
    Write-Output 'All builds and checks passed. Deployment files are ready.'
} finally {
    Pop-Location
}
