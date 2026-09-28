Add-Type @'
using System;
using System.Runtime.InteropServices;
using System.Text;
using System.Drawing;

public class WinElementFinder {
    [DllImport("user32.dll", SetLastError = true)]
    public static extern IntPtr FindWindow(string lpClassName, string lpWindowName);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern IntPtr FindWindowEx(IntPtr hwndParent, IntPtr hwndChildAfter, string lpszClass, string lpszWindow);

    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    public static string FindWindowCenter(string query) {
        // Search running processes for matching window title or process name
        foreach (var p in System.Diagnostics.Process.GetProcesses()) {
            try {
                if (p.MainWindowHandle != IntPtr.Zero && !string.IsNullOrEmpty(p.MainWindowTitle)) {
                    if (p.ProcessName.IndexOf(query, StringComparison.OrdinalIgnoreCase) >= 0 ||
                        p.MainWindowTitle.IndexOf(query, StringComparison.OrdinalIgnoreCase) >= 0) {
                        RECT r;
                        if (GetWindowRect(p.MainWindowHandle, out r)) {
                            int cx = (r.Left + r.Right) / 2;
                            int cy = (r.Top + r.Bottom) / 2;
                            return cx + "," + cy + "|" + p.ProcessName + "|" + p.MainWindowTitle;
                        }
                    }
                }
            } catch {}
        }
        return "NOT_FOUND";
    }
}
'@ -ReferencedAssemblies System.Drawing

$res = [WinElementFinder]::FindWindowCenter("Nilar")
Write-Output "Result: $res"
