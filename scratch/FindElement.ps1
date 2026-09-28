Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$target = $args[0]
if (-not $target) { $target = "Start" }

$root = [System.Windows.Automation.AutomationElement]::RootElement

# 1. Check desktop icons (Progman -> SysListView32)
$desktop = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, "Progman")))
if (-not $desktop) {
    # Check WorkerW
    $desktop = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, "WorkerW")))
}

# Search all top-level and second-level elements
$elements = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::IsOffscreenProperty, $false)))

$found = $null
foreach ($el in $elements) {
    try {
        $name = $el.Current.Name
        if ($name -and ($name -like "*$target*" -or $target -like "*$name*")) {
            $rect = $el.Current.BoundingRectangle
            if ($rect.Width -gt 5 -and $rect.Height -gt 5) {
                $centerX = [int]($rect.X + ($rect.Width / 2))
                $centerY = [int]($rect.Y + ($rect.Height / 2))
                $found = "$centerX,$centerY,$name,$($rect.Width)x$($rect.Height)"
                break
            }
        }
    } catch {}
}

if ($found) {
    Write-Output $found
} else {
    Write-Output "NOT_FOUND"
}
