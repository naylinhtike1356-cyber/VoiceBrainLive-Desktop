Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::RootElement
$children = $root.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)

foreach ($c in $children) {
    try {
        $n = $c.Current.Name
        $cn = $c.Current.ClassName
        $rect = $c.Current.BoundingRectangle
        Write-Output "CHILD: Name='$n' Class='$cn' Rect=$($rect.X),$($rect.Y),$($rect.Width),$($rect.Height)"
    } catch {}
}
