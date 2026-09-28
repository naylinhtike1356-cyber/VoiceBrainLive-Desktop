Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
$root=[System.Windows.Automation.AutomationElement]::RootElement
$top=$root.FindAll([System.Windows.Automation.TreeScope]::Children,[System.Windows.Automation.Condition]::TrueCondition)
foreach($e in $top){try{if($e.Current.Name -like 'Nilar AI*'){ $r=$e.Current.BoundingRectangle; Write-Output ("HANDLE={0} NAME={1} X={2} Y={3} W={4} H={5}" -f $e.Current.NativeWindowHandle,$e.Current.Name,$r.X,$r.Y,$r.Width,$r.Height)}}catch{}}
