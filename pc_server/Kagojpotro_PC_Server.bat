@echo off
setlocal
set "BAT_SELF=%~f0"
set "PS_FILE=%TEMP%\Kagojpotro_PC_Server_GUI.ps1"
set "VBS_FILE=%TEMP%\Kagojpotro_PC_Server_Launch.vbs"
powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command "try { Unblock-File -LiteralPath $env:BAT_SELF -ErrorAction SilentlyContinue } catch {}; $lines = [System.IO.File]::ReadAllLines($env:BAT_SELF); $idx = [Array]::IndexOf($lines, '# ===KAGOJPOTRO_PS_BODY_START==='); if ($idx -lt 0) { $idx = 11 }; [System.IO.File]::WriteAllLines($env:PS_FILE, $lines[($idx+1)..($lines.Length-1)], [System.Text.Encoding]::UTF8)"
> "%VBS_FILE%" echo Set ws = CreateObject("WScript.Shell")
>> "%VBS_FILE%" echo psCmd = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -STA -File """ ^& WScript.Arguments(0) ^& """ """ ^& WScript.Arguments(1) ^& """"
>> "%VBS_FILE%" echo ws.Run psCmd, 0, False
start "" wscript.exe //nologo "%VBS_FILE%" "%PS_FILE%" "%BAT_SELF%"
exit /b
# ===KAGOJPOTRO_PS_BODY_START===
# ==============================================================================
# KAGOJPOTRO -- MINIMAL 1-CLICK INTERNET PC SERVER (SYSTEM TRAY + SHORT URL)
# ==============================================================================
param([string]$OriginalBatPath = "")

$ErrorActionPreference = "Continue"

try {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [System.Windows.Forms.Application]::EnableVisualStyles()
    [Net.ServicePointManager]::Expect100Continue = $false
    try {
        [Net.ServicePointManager]::SecurityProtocol = 3072 -bor 12288
    } catch {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    }
} catch {
    try {
        [System.Windows.Forms.MessageBox]::Show("Failed to load Windows Forms: $_", "Kagojpotro Server") | Out-Null
    } catch {}
    exit 1
}

$script:InstanceMutexCreated = $false
$script:InstanceMutex = $null
try {
    $script:InstanceMutex = New-Object System.Threading.Mutex($true, "Local\Kagojpotro_PC_Server_SingleInstance", [ref]$script:InstanceMutexCreated)
    if (-not $script:InstanceMutexCreated) {
        [System.Windows.Forms.MessageBox]::Show("Kagojpotro PC Server is already running. Check your System Tray or taskbar.", "Kagojpotro Server", [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Information) | Out-Null
        exit 0
    }
} catch {}

# Belt-and-suspenders: hide any console window if this .ps1 is launched directly
try {
    Add-Type -Name Win32HideConsole -Namespace KagojpotroWin32 -MemberDefinition '
        [System.Runtime.InteropServices.DllImport("kernel32.dll")]
        public static extern IntPtr GetConsoleWindow();
        [System.Runtime.InteropServices.DllImport("user32.dll")]
        public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
        [System.Runtime.InteropServices.DllImport("user32.dll", SetLastError = true)]
        public static extern bool DestroyIcon(IntPtr hIcon);
    ' -ErrorAction SilentlyContinue
    $cw = [KagojpotroWin32.Win32HideConsole]::GetConsoleWindow()
    if ($cw -ne [IntPtr]::Zero) {
        [KagojpotroWin32.Win32HideConsole]::ShowWindow($cw, 0) | Out-Null
    }
} catch {}

# Automatically strip Windows Mark-of-the-Web (Zone.Identifier) so "Open File - Security Warning" does not appear again
try {
    if ($OriginalBatPath -and (Test-Path -LiteralPath $OriginalBatPath)) {
        Unblock-File -LiteralPath $OriginalBatPath -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath ($OriginalBatPath + ":Zone.Identifier") -Force -ErrorAction SilentlyContinue
    }
} catch {}

# Exact Bangla title "কাগজপত্র" constructed via Unicode code points so encoding never corrupts it
$BanglaTitle = -join @(
    [char]0x0995, [char]0x09BE, [char]0x0997, [char]0x099C,
    [char]0x09AA, [char]0x09A4, [char]0x09CD, [char]0x09B0
)
$WindowTitle = "$BanglaTitle Server setup"

$AppDataDir = Join-Path $env:APPDATA "KagojpotroServer"
if (-not (Test-Path $AppDataDir)) {
    New-Item -ItemType Directory -Path $AppDataDir -Force | Out-Null
}
$ConfigFile = Join-Path $AppDataDir "config.json"
$CloudflaredExe = Join-Path $AppDataDir "cloudflared.exe"
$TunnelLogFile = Join-Path $AppDataDir "tunnel.log"

# Always enable Auto-Start on Windows boot by default and unblock the startup script
try {
    $StartupLinkFile = Join-Path ([Environment]::GetFolderPath("Startup")) "Kagojpotro_PC_Server.bat"
    if ($OriginalBatPath -and (Test-Path -LiteralPath $OriginalBatPath)) {
        Copy-Item -LiteralPath $OriginalBatPath -Destination $StartupLinkFile -Force -ErrorAction SilentlyContinue
        Unblock-File -LiteralPath $StartupLinkFile -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath ($StartupLinkFile + ":Zone.Identifier") -Force -ErrorAction SilentlyContinue
    }
} catch {}

$Config = @{
    StoragePath = ""
    Port        = 8085
}

if (Test-Path $ConfigFile) {
    try {
        $loaded = Get-Content $ConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($loaded.StoragePath) { $Config.StoragePath = [string]$loaded.StoragePath }
        if ($loaded.Port) { $Config.Port = [int]$loaded.Port }
    } catch {}
}

function Save-ServerConfig {
    try {
        $Config | ConvertTo-Json | Set-Content -Path $ConfigFile -Encoding UTF8
    } catch {}
}

function Select-StorageDirectory([string]$currentPath) {
    $dlg = New-Object System.Windows.Forms.FolderBrowserDialog
    $dlg.Description = "Select PC Storage Folder:"
    $dlg.ShowNewFolderButton = $true
    if ($currentPath -and (Test-Path $currentPath)) {
        $dlg.SelectedPath = $currentPath
    }
    $owner = New-Object System.Windows.Forms.Form
    $owner.TopMost = $true
    $res = $dlg.ShowDialog($owner)
    $owner.Dispose()
    if ($res -eq [System.Windows.Forms.DialogResult]::OK -and $dlg.SelectedPath) {
        return $dlg.SelectedPath
    }
    return $null
}

if (-not $Config.StoragePath -or -not (Test-Path $Config.StoragePath)) {
    $chosen = Select-StorageDirectory ""
    if (-not $chosen) {
        exit 0
    }
    $Config.StoragePath = $chosen
    Save-ServerConfig
}

function New-KagojpotroLogoBitmap([int]$size = 32) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $bgBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(15, 139, 109))
    $pad = [Math]::Max(1, [int]($size * 0.04))
    $boxSize = $size - ($pad * 2)
    $g.FillEllipse($bgBrush, $pad, $pad, $boxSize, $boxSize)

    $backPageBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(190, 245, 232))
    $g.FillRectangle($backPageBrush, [int]($size * 0.34), [int]($size * 0.18), [int]($size * 0.42), [int]($size * 0.56))

    $docBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $docX = [int]($size * 0.22)
    $docY = [int]($size * 0.24)
    $docW = [int]($size * 0.46)
    $docH = [int]($size * 0.56)
    $g.FillRectangle($docBrush, $docX, $docY, $docW, $docH)

    $penWidth = [Math]::Max(1.5, [float]($size * 0.06))
    $linePen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(15, 139, 109), $penWidth)
    $lx1 = $docX + [int]($docW * 0.18)
    $lx2 = $docX + [int]($docW * 0.82)
    $g.DrawLine($linePen, $lx1, $docY + [int]($docH * 0.30), $lx2, $docY + [int]($docH * 0.30))
    $g.DrawLine($linePen, $lx1, $docY + [int]($docH * 0.54), $lx2, $docY + [int]($docH * 0.54))
    $g.DrawLine($linePen, $lx1, $docY + [int]($docH * 0.76), $docX + [int]($docW * 0.62), $docY + [int]($docH * 0.76))

    $linePen.Dispose()
    $docBrush.Dispose()
    $backPageBrush.Dispose()
    $bgBrush.Dispose()
    $g.Dispose()
    return $bmp
}

function New-FolderIconBitmap([int]$size = 20) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $tabBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(16, 185, 129))
    $bodyBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(52, 211, 153))

    $g.FillRectangle($tabBrush, 2, 3, 7, 4)
    $g.FillRectangle($bodyBrush, 2, 6, ($size - 4), ($size - 9))

    $bodyBrush.Dispose()
    $tabBrush.Dispose()
    $g.Dispose()
    return $bmp
}

function New-StatusDotBitmap([System.Drawing.Color]$fillColor, [int]$size = 18) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $glowColor = [System.Drawing.Color]::FromArgb(55, $fillColor.R, $fillColor.G, $fillColor.B)
    $glowBrush = New-Object System.Drawing.SolidBrush($glowColor)
    $g.FillEllipse($glowBrush, 1, 1, ($size - 2), ($size - 2))

    $dotBrush = New-Object System.Drawing.SolidBrush($fillColor)
    $g.FillEllipse($dotBrush, 3, 3, ($size - 6), ($size - 6))

    $ringPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(210, 255, 255, 255), 1.3)
    $g.DrawEllipse($ringPen, 3, 3, ($size - 6), ($size - 6))

    $ringPen.Dispose()
    $dotBrush.Dispose()
    $glowBrush.Dispose()
    $g.Dispose()
    return $bmp
}

# Compile the embedded zero-admin C# HTTP Server engine (.NET TcpListener)
$CSharpServerCode = @'
using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;

namespace KagojpotroServerEngine {
    public class KagojpotroTcpServer {
        private TcpListener _listener;
        private Thread _acceptThread;
        private volatile bool _running;
        private long _lastClientActivityTicks;
        private readonly object _syncLock = new object();
        public string StorageRoot { get; set; }
        public int Port { get; set; }
        public bool IsRunning { get { return _running; } }

        public DateTime LastClientActivityUtc {
            get {
                long ticks = Interlocked.Read(ref _lastClientActivityTicks);
                return ticks <= 0L ? DateTime.MinValue : new DateTime(ticks, DateTimeKind.Utc);
            }
        }

        public void ResetClientActivity() {
            Interlocked.Exchange(ref _lastClientActivityTicks, 0L);
        }

        private void MarkClientActivity() {
            Interlocked.Exchange(ref _lastClientActivityTicks, DateTime.UtcNow.Ticks);
        }

        public KagojpotroTcpServer(string storageRoot, int port) {
            StorageRoot = storageRoot;
            Port = port;
        }

        public void Start() {
            if (_running) return;
            ResetClientActivity();
            _listener = new TcpListener(IPAddress.Any, Port);
            try { _listener.ExclusiveAddressUse = true; } catch {}
            _listener.Start();
            _running = true;
            _acceptThread = new Thread(AcceptLoop) { IsBackground = true };
            _acceptThread.Start();
        }

        public void Stop() {
            _running = false;
            ResetClientActivity();
            try { if (_listener != null) _listener.Stop(); } catch {}
            _listener = null;
        }

        private void AcceptLoop() {
            while (_running) {
                try {
                    TcpListener listener = _listener;
                    if (listener == null) break;
                    TcpClient client = listener.AcceptTcpClient();
                    ThreadPool.QueueUserWorkItem(HandleClient, client);
                } catch {
                    if (!_running) break;
                }
            }
        }

        private static string SanitizeUser(string raw) {
            if (string.IsNullOrWhiteSpace(raw)) return "default";
            string part = raw.Trim().ToLowerInvariant().Split('@')[0];
            string clean = Regex.Replace(part, @"[^a-z0-9_-]", "_").Trim('_');
            return string.IsNullOrEmpty(clean) ? "default" : clean;
        }

        private static string SanitizeSegment(string raw) {
            if (string.IsNullOrWhiteSpace(raw)) return "Untitled_Folder";
            string clean = Regex.Replace(raw.Trim(), "[\\\\/:*?\"<>|]", "_");
            return string.IsNullOrEmpty(clean) ? "Untitled_Folder" : clean;
        }

        private string EnsureUserRoot(string user) {
            string uRoot = Path.Combine(StorageRoot, "users", SanitizeUser(user));
            Directory.CreateDirectory(Path.Combine(uRoot, "Folders"));
            Directory.CreateDirectory(Path.Combine(uRoot, ".system", "edited"));
            Directory.CreateDirectory(Path.Combine(uRoot, ".system", "original"));
            Directory.CreateDirectory(Path.Combine(uRoot, ".system", "thumb"));
            return uRoot;
        }

        private long ComputeUserBytes(string user) {
            string sysDir = Path.Combine(EnsureUserRoot(user), ".system");
            long total = 0;
            try {
                foreach (string f in Directory.GetFiles(sysDir, "*", SearchOption.AllDirectories)) {
                    try { total += new FileInfo(f).Length; } catch {}
                }
            } catch {}
            return total;
        }

        private void GetDriveStats(out long freeBytes, out long totalBytes) {
            freeBytes = 0;
            totalBytes = 0;
            try {
                string rootDrive = Path.GetPathRoot(Path.GetFullPath(StorageRoot));
                DriveInfo di = new DriveInfo(rootDrive);
                freeBytes = di.AvailableFreeSpace;
                totalBytes = di.TotalSize;
            } catch {}
        }

        private void HandleClient(object state) {
            using (TcpClient client = (TcpClient)state) {
                client.ReceiveTimeout = 30000;
                client.SendTimeout = 30000;
                try { client.NoDelay = true; } catch {}
                try { client.LingerState = new LingerOption(true, 3); } catch {}
                NetworkStream rawStream = null;
                try {
                    rawStream = client.GetStream();
                    BufferedStream stream = new BufferedStream(rawStream, 65536);
                    string requestLine = ReadLineAscii(stream);
                    if (string.IsNullOrEmpty(requestLine)) return;

                    string[] parts = requestLine.Split(' ');
                    if (parts.Length < 2) return;
                    string method = parts[0].ToUpperInvariant();
                    string rawTarget = parts[1];

                    Dictionary<string, string> headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                    string line;
                    while (!string.IsNullOrEmpty(line = ReadLineAscii(stream))) {
                        int colon = line.IndexOf(':');
                        if (colon > 0) {
                            headers[line.Substring(0, colon).Trim()] = line.Substring(colon + 1).Trim();
                        }
                    }

                    long contentLength = 0;
                    if (headers.ContainsKey("Content-Length")) {
                        long.TryParse(headers["Content-Length"], out contentLength);
                    }

                    string path = rawTarget;
                    string query = "";
                    int qIdx = rawTarget.IndexOf('?');
                    if (qIdx >= 0) {
                        path = rawTarget.Substring(0, qIdx);
                        query = rawTarget.Substring(qIdx + 1);
                    }
                    Dictionary<string, string> qParams = ParseQuery(query);
                    string userDir = qParams.ContainsKey("user") ? SanitizeUser(qParams["user"]) : "default";
                    if (headers.ContainsKey("X-Kagojpotro-User") && !string.IsNullOrWhiteSpace(headers["X-Kagojpotro-User"])) {
                        userDir = SanitizeUser(headers["X-Kagojpotro-User"]);
                    }

                    string uRoot = EnsureUserRoot(userDir);
                    string manifestPath = Path.Combine(uRoot, "manifest.json");
                    JavaScriptSerializer json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };

                    if (path.Equals("/api/disconnect", StringComparison.OrdinalIgnoreCase)) {
                        ResetClientActivity();
                        WriteJsonResponse(stream, 200, "{\"disconnected\":true}");
                        return;
                    }

                    if (path.Equals("/", StringComparison.OrdinalIgnoreCase) ||
                        path.Equals("/api/ping", StringComparison.OrdinalIgnoreCase)) {
                        MarkClientActivity();
                        long freeBytes, totalBytes;
                        GetDriveStats(out freeBytes, out totalBytes);
                        long usedBytes = ComputeUserBytes(userDir);
                        string resp = json.Serialize(new Dictionary<string, object> {
                            { "ok", true },
                            { "status", "ok" },
                            { "app", "Kagojpotro PC Server" },
                            { "user", userDir },
                            { "userDirectory", userDir },
                            { "freeSpaceBytes", freeBytes },
                            { "totalSpaceBytes", totalBytes },
                            { "userUsedBytes", usedBytes }
                        });
                        WriteJsonResponse(stream, 200, resp);
                        return;
                    }

                    if (path.Equals("/api/manifest", StringComparison.OrdinalIgnoreCase)) {
                        MarkClientActivity();
                        if (method == "GET") {
                            lock (_syncLock) {
                                if (File.Exists(manifestPath)) {
                                    string content = File.ReadAllText(manifestPath, Encoding.UTF8);
                                    WriteJsonResponse(stream, 200, content);
                                } else {
                                    WriteJsonResponse(stream, 200, "{\"updatedAt\":0,\"folders\":[],\"scans\":[]}");
                                }
                            }
                            return;
                        } else if (method == "POST") {
                            if (contentLength <= 0 || contentLength > 64L * 1024L * 1024L) {
                                WriteJsonResponse(stream, 400, "{\"error\":\"Invalid manifest length\"}");
                                return;
                            }
                            byte[] body = ReadExactBytes(stream, contentLength);
                            if (body == null) {
                                WriteJsonResponse(stream, 400, "{\"error\":\"Incomplete manifest body\"}");
                                return;
                            }
                            string bodyStr = Encoding.UTF8.GetString(body);
                            try {
                                var parsed = json.Deserialize<Dictionary<string, object>>(bodyStr);
                                if (parsed == null) {
                                    WriteJsonResponse(stream, 400, "{\"error\":\"Invalid JSON manifest\"}");
                                    return;
                                }
                            } catch {
                                WriteJsonResponse(stream, 400, "{\"error\":\"Invalid JSON manifest\"}");
                                return;
                            }
                            lock (_syncLock) {
                                string tmpManifest = manifestPath + ".part";
                                File.WriteAllText(tmpManifest, bodyStr, Encoding.UTF8);
                                if (File.Exists(manifestPath)) File.Delete(manifestPath);
                                File.Move(tmpManifest, manifestPath);
                                MirrorHumanReadableFolders(uRoot, bodyStr, json);
                            }
                            long freeBytes, totalBytes;
                            GetDriveStats(out freeBytes, out totalBytes);
                            long usedBytes = ComputeUserBytes(userDir);
                            string okJson = json.Serialize(new Dictionary<string, object> {
                                { "saved", true },
                                { "freeSpaceBytes", freeBytes },
                                { "totalSpaceBytes", totalBytes },
                                { "userUsedBytes", usedBytes }
                            });
                            WriteJsonResponse(stream, 200, okJson);
                            return;
                        }
                    }

                    string fileKind = null;
                    string fileScanId = null;
                    if (path.Equals("/api/file", StringComparison.OrdinalIgnoreCase)) {
                        fileKind = qParams.ContainsKey("kind") ? qParams["kind"].ToLowerInvariant() : "edited";
                        string rawScanId = qParams.ContainsKey("scanId") ? qParams["scanId"] : "";
                        fileScanId = Regex.Replace(rawScanId, @"[^a-zA-Z0-9_-]", "");
                    } else if (path.StartsWith("/api/files/", StringComparison.OrdinalIgnoreCase)) {
                        string[] segs = path.Substring("/api/files/".Length).Split('/');
                        if (segs.Length >= 2) {
                            fileKind = segs[0].ToLowerInvariant();
                            fileScanId = Regex.Replace(segs[1], @"[^a-zA-Z0-9_-]", "");
                        }
                    }

                    if (fileKind != null) {
                        if (string.IsNullOrEmpty(fileScanId)) {
                            WriteJsonResponse(stream, 400, "{\"error\":\"Invalid scan id\"}");
                            return;
                        }
                        MarkClientActivity();
                        string subFolder = fileKind == "original" ? "original" : (fileKind == "thumb" ? "thumb" : "edited");
                        string prefix = fileKind == "original" ? "orig_" : (fileKind == "thumb" ? "thumb_" : "edit_");
                        string sysFile = Path.Combine(uRoot, ".system", subFolder, prefix + fileScanId + ".jpg");

                        if (method == "PUT" || method == "POST") {
                            if (contentLength < 0) {
                                WriteJsonResponse(stream, 400, "{\"error\":\"Invalid Content-Length\"}");
                                return;
                            }
                            Directory.CreateDirectory(Path.GetDirectoryName(sysFile));
                            if (!CopyStreamToFile(stream, contentLength, sysFile)) {
                                WriteJsonResponse(stream, 400, "{\"error\":\"Incomplete file upload\"}");
                                return;
                            }
                            WriteJsonResponse(stream, 200, "{\"uploaded\":true}");
                            return;
                        } else if (method == "GET") {
                            if (!File.Exists(sysFile) && fileKind != "edited") {
                                string fallback = Path.Combine(uRoot, ".system", "edited", "edit_" + fileScanId + ".jpg");
                                if (File.Exists(fallback)) sysFile = fallback;
                            }
                            if (File.Exists(sysFile)) {
                                byte[] fBytes = File.ReadAllBytes(sysFile);
                                string header = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + fBytes.Length + "\r\nConnection: close\r\n\r\n";
                                byte[] hBytes = Encoding.ASCII.GetBytes(header);
                                stream.Write(hBytes, 0, hBytes.Length);
                                stream.Write(fBytes, 0, fBytes.Length);
                                stream.Flush();
                            } else {
                                WriteJsonResponse(stream, 404, "{\"error\":\"File not found\"}");
                            }
                            return;
                        }
                    }

                    WriteJsonResponse(stream, 404, "{\"error\":\"Not found\"}");
                } catch {
                    try {
                        if (rawStream != null) {
                            WriteJsonResponse(rawStream, 500, "{\"ok\":false,\"error\":\"Internal server error\"}");
                        }
                    } catch {}
                } finally {
                    try { if (rawStream != null) rawStream.Flush(); } catch {}
                    try { client.Client.Shutdown(SocketShutdown.Send); } catch {}
                }
            }
        }

        private void MirrorHumanReadableFolders(string uRoot, string manifestJson, JavaScriptSerializer json) {
            try {
                var root = json.Deserialize<Dictionary<string, object>>(manifestJson);
                if (root == null) return;

                Dictionary<string, Dictionary<string, object>> folderMap = new Dictionary<string, Dictionary<string, object>>();
                if (root.ContainsKey("folders") && root["folders"] is System.Collections.ArrayList) {
                    foreach (object fObj in (System.Collections.ArrayList)root["folders"]) {
                        var f = fObj as Dictionary<string, object>;
                        if (f != null && f.ContainsKey("id")) {
                            folderMap[Convert.ToString(f["id"])] = f;
                        }
                    }
                }

                Dictionary<string, string> folderRelativePaths = BuildAllFolderRelativePaths(folderMap);

                string visibleRoot = Path.Combine(uRoot, "Folders");
                Directory.CreateDirectory(visibleRoot);

                HashSet<string> expectedDirs = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                expectedDirs.Add(Path.GetFullPath(visibleRoot));
                foreach (var kv in folderRelativePaths) {
                    string rel = kv.Value;
                    if (!string.IsNullOrEmpty(rel)) {
                        string dirPath = Path.Combine(visibleRoot, rel);
                        Directory.CreateDirectory(dirPath);
                        expectedDirs.Add(Path.GetFullPath(dirPath));
                    }
                }

                HashSet<string> expectedFiles = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                Dictionary<string, HashSet<string>> usedNamesByDir = new Dictionary<string, HashSet<string>>(StringComparer.OrdinalIgnoreCase);

                if (root.ContainsKey("scans") && root["scans"] is System.Collections.ArrayList) {
                    foreach (object sObj in (System.Collections.ArrayList)root["scans"]) {
                        var s = sObj as Dictionary<string, object>;
                        if (s == null || !s.ContainsKey("id") || !s.ContainsKey("folderId")) continue;
                        string scanId = Regex.Replace(Convert.ToString(s["id"]) ?? "", @"[^a-zA-Z0-9_-]", "");
                        string folderId = Convert.ToString(s["folderId"]);
                        if (string.IsNullOrEmpty(scanId) || !folderMap.ContainsKey(folderId)) continue;
                        string rawFileName = s.ContainsKey("filename") ? Convert.ToString(s["filename"]) : ("Kagojpotro_" + scanId + ".jpg");
                        if (!rawFileName.EndsWith(".jpg", StringComparison.OrdinalIgnoreCase)) rawFileName += ".jpg";
                        string safeName = SanitizeSegment(rawFileName);
                        if (!safeName.EndsWith(".jpg", StringComparison.OrdinalIgnoreCase)) safeName += ".jpg";

                        string relFolder = folderRelativePaths.ContainsKey(folderId) ? folderRelativePaths[folderId] : "";
                        string targetDir = string.IsNullOrEmpty(relFolder) ? visibleRoot : Path.Combine(visibleRoot, relFolder);
                        Directory.CreateDirectory(targetDir);
                        string fullDir = Path.GetFullPath(targetDir);
                        expectedDirs.Add(fullDir);

                        if (!usedNamesByDir.ContainsKey(fullDir)) {
                            usedNamesByDir[fullDir] = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                        }
                        var usedSet = usedNamesByDir[fullDir];
                        string finalName = safeName;
                        if (usedSet.Contains(finalName)) {
                            string stem = Path.GetFileNameWithoutExtension(safeName);
                            string shortId = scanId.Length >= 4 ? scanId.Substring(0, 4) : scanId;
                            finalName = stem + "_" + shortId + ".jpg";
                        }
                        usedSet.Add(finalName);

                        string visibleFile = Path.Combine(targetDir, finalName);
                        expectedFiles.Add(Path.GetFullPath(visibleFile));

                        string sysEdited = Path.Combine(uRoot, ".system", "edited", "edit_" + scanId + ".jpg");
                        if (File.Exists(sysEdited)) {
                            try {
                                bool shouldCopy = !File.Exists(visibleFile) ||
                                    File.GetLastWriteTimeUtc(sysEdited) > File.GetLastWriteTimeUtc(visibleFile) ||
                                    new FileInfo(sysEdited).Length != new FileInfo(visibleFile).Length;
                                if (shouldCopy) {
                                    File.Copy(sysEdited, visibleFile, true);
                                }
                            } catch {}
                        }
                    }
                }

                try {
                    foreach (string existingFile in Directory.GetFiles(visibleRoot, "*.jpg", SearchOption.AllDirectories)) {
                        if (!expectedFiles.Contains(Path.GetFullPath(existingFile))) {
                            try { File.Delete(existingFile); } catch {}
                        }
                    }
                    string[] allDirs = Directory.GetDirectories(visibleRoot, "*", SearchOption.AllDirectories);
                    Array.Sort(allDirs, (a, b) => b.Length.CompareTo(a.Length));
                    foreach (string d in allDirs) {
                        if (!expectedDirs.Contains(Path.GetFullPath(d))) {
                            try {
                                if (Directory.GetFiles(d).Length == 0 && Directory.GetDirectories(d).Length == 0) {
                                    Directory.Delete(d, false);
                                }
                            } catch {}
                        }
                    }
                } catch {}
            } catch {}
        }

        private Dictionary<string, string> BuildAllFolderRelativePaths(Dictionary<string, Dictionary<string, object>> map) {
            Dictionary<string, string> resolved = new Dictionary<string, string>();
            Dictionary<string, HashSet<string>> usedChildNames = new Dictionary<string, HashSet<string>>(StringComparer.OrdinalIgnoreCase);
            List<string> orderedIds = new List<string>(map.Keys);
            orderedIds.Sort(StringComparer.Ordinal);

            Dictionary<string, string> uniqueSegmentById = new Dictionary<string, string>();
            foreach (string folderId in orderedIds) {
                var f = map[folderId];
                string parentKey = (f.ContainsKey("parentId") && f["parentId"] != null) ? Convert.ToString(f["parentId"]) : "";
                if (!map.ContainsKey(parentKey)) parentKey = "";
                if (!usedChildNames.ContainsKey(parentKey)) {
                    usedChildNames[parentKey] = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                }
                string rawName = f.ContainsKey("name") ? SanitizeSegment(Convert.ToString(f["name"])) : "Folder";
                string candidate = rawName;
                if (usedChildNames[parentKey].Contains(candidate)) {
                    string safeId = Regex.Replace(folderId, @"[^a-zA-Z0-9_-]", "");
                    string shortId = safeId.Length >= 4 ? safeId.Substring(0, 4) : (safeId.Length > 0 ? safeId : "dup");
                    candidate = rawName + "_" + shortId;
                    int suffix = 2;
                    while (usedChildNames[parentKey].Contains(candidate)) {
                        candidate = rawName + "_" + shortId + "_" + suffix;
                        suffix++;
                    }
                }
                usedChildNames[parentKey].Add(candidate);
                uniqueSegmentById[folderId] = candidate;
            }

            foreach (string folderId in orderedIds) {
                List<string> segments = new List<string>();
                HashSet<string> visited = new HashSet<string>();
                string curr = folderId;
                while (!string.IsNullOrEmpty(curr) && map.ContainsKey(curr) && visited.Add(curr)) {
                    segments.Add(uniqueSegmentById.ContainsKey(curr) ? uniqueSegmentById[curr] : "Folder");
                    var f = map[curr];
                    curr = (f.ContainsKey("parentId") && f["parentId"] != null) ? Convert.ToString(f["parentId"]) : null;
                }
                segments.Reverse();
                resolved[folderId] = string.Join(Path.DirectorySeparatorChar.ToString(), segments.ToArray());
            }
            return resolved;
        }

        private static byte[] ReadExactBytes(Stream stream, long length) {
            if (length < 0 || length > 64L * 1024L * 1024L) return null;
            byte[] buffer = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = stream.Read(buffer, offset, (int)Math.Min(65536, length - offset));
                if (read <= 0) break;
                offset += read;
            }
            return offset == length ? buffer : null;
        }

        private static bool CopyStreamToFile(Stream stream, long length, string filePath) {
            if (length < 0) return false;
            string partPath = filePath + ".part";
            long remaining = length;
            try {
                using (FileStream fs = new FileStream(partPath, FileMode.Create, FileAccess.Write)) {
                    byte[] buf = new byte[65536];
                    while (remaining > 0) {
                        int toRead = (int)Math.Min(buf.Length, remaining);
                        int read = stream.Read(buf, 0, toRead);
                        if (read <= 0) break;
                        fs.Write(buf, 0, read);
                        remaining -= read;
                    }
                    fs.Flush();
                }
                if (remaining == 0) {
                    if (File.Exists(filePath)) File.Delete(filePath);
                    File.Move(partPath, filePath);
                    return true;
                }
            } catch {}
            try { if (File.Exists(partPath)) File.Delete(partPath); } catch {}
            return false;
        }

        private static string ReadLineAscii(Stream stream) {
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = stream.ReadByte()) != -1) {
                if (b == '\r') continue;
                if (b == '\n') break;
                if (sb.Length >= 16384) return null;
                sb.Append((char)b);
            }
            return sb.Length == 0 && b == -1 ? null : sb.ToString();
        }

        private static Dictionary<string, string> ParseQuery(string q) {
            Dictionary<string, string> res = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            if (string.IsNullOrEmpty(q)) return res;
            foreach (string pair in q.Split('&')) {
                int idx = pair.IndexOf('=');
                if (idx > 0) {
                    string k = Uri.UnescapeDataString(pair.Substring(0, idx));
                    string v = Uri.UnescapeDataString(pair.Substring(idx + 1).Replace('+', ' '));
                    res[k] = v;
                }
            }
            return res;
        }

        private static void WriteJsonResponse(Stream stream, int status, string json) {
            byte[] body = Encoding.UTF8.GetBytes(json);
            string statusText = status == 200 ? "OK" : (status == 404 ? "Not Found" : "Error");
            string header = "HTTP/1.1 " + status + " " + statusText + "\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + body.Length + "\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n";
            byte[] hBytes = Encoding.ASCII.GetBytes(header);
            byte[] packet = new byte[hBytes.Length + body.Length];
            Buffer.BlockCopy(hBytes, 0, packet, 0, hBytes.Length);
            Buffer.BlockCopy(body, 0, packet, hBytes.Length, body.Length);
            stream.Write(packet, 0, packet.Length);
            stream.Flush();
        }
    }

    public static class SimpleQrGenerator {
        private struct QrSpec {
            public int Version;
            public int EcIndicator; // 1=L, 0=M
            public int DataCwPerBlock;
            public int EcCwPerBlock;
            public int Blocks;
            public int MaxBytes;
            public int[] Align;
            public QrSpec(int v, int ecInd, int dpb, int ecpb, int b, int maxB, int[] align) {
                Version = v; EcIndicator = ecInd; DataCwPerBlock = dpb;
                EcCwPerBlock = ecpb; Blocks = b; MaxBytes = maxB; Align = align;
            }
        }

        private static readonly QrSpec[] Specs = new QrSpec[] {
            new QrSpec(1, 0, 16, 10, 1, 14, new int[0]),
            new QrSpec(2, 0, 28, 16, 1, 26, new int[] { 6, 18 }),
            new QrSpec(3, 0, 44, 26, 1, 42, new int[] { 6, 22 }),
            new QrSpec(4, 0, 32, 18, 2, 62, new int[] { 6, 26 }),
            new QrSpec(5, 0, 43, 24, 2, 84, new int[] { 6, 30 }),
            new QrSpec(6, 0, 27, 16, 4, 106, new int[] { 6, 34 }),
            new QrSpec(6, 1, 68, 18, 2, 134, new int[] { 6, 34 }),
            new QrSpec(7, 1, 78, 20, 2, 154, new int[] { 6, 22, 38 }),
            new QrSpec(8, 1, 97, 24, 2, 192, new int[] { 6, 24, 42 }),
            new QrSpec(9, 1, 116, 30, 2, 230, new int[] { 6, 26, 46 })
        };

        public static Bitmap RenderUrlQrBitmap(string text, int targetPixels) {
            if (string.IsNullOrEmpty(text)) return null;
            byte[] utf8 = Encoding.UTF8.GetBytes(text);
            QrSpec spec = Specs[Specs.Length - 1];
            bool found = false;
            foreach (QrSpec s in Specs) {
                if (utf8.Length <= s.MaxBytes) {
                    spec = s;
                    found = true;
                    break;
                }
            }
            if (!found && utf8.Length > spec.MaxBytes) {
                byte[] clipped = new byte[spec.MaxBytes];
                Array.Copy(utf8, clipped, spec.MaxBytes);
                utf8 = clipped;
            }

            int totalDataCw = spec.DataCwPerBlock * spec.Blocks;
            List<int> bits = new List<int>(totalDataCw * 8);
            AppendBits(bits, 4, 4); // Byte mode
            AppendBits(bits, utf8.Length, 8);
            foreach (byte b in utf8) AppendBits(bits, b, 8);

            int totalDataBits = totalDataCw * 8;
            int terminator = Math.Min(4, totalDataBits - bits.Count);
            AppendBits(bits, 0, terminator);
            while (bits.Count % 8 != 0) bits.Add(0);
            int padIdx = 0;
            while (bits.Count < totalDataBits) {
                AppendBits(bits, (padIdx % 2 == 0) ? 0xEC : 0x11, 8);
                padIdx++;
            }

            byte[] dataCw = new byte[totalDataCw];
            for (int i = 0; i < totalDataCw; i++) {
                int v = 0;
                for (int b = 0; b < 8; b++) v = (v << 1) | bits[i * 8 + b];
                dataCw[i] = (byte)v;
            }

            byte[] gen = BuildRsGenerator(spec.EcCwPerBlock);
            byte[][] dBlocks = new byte[spec.Blocks][];
            byte[][] ecBlocks = new byte[spec.Blocks][];
            for (int b = 0; b < spec.Blocks; b++) {
                dBlocks[b] = new byte[spec.DataCwPerBlock];
                Array.Copy(dataCw, b * spec.DataCwPerBlock, dBlocks[b], 0, spec.DataCwPerBlock);
                ecBlocks[b] = ComputeRsEc(dBlocks[b], gen, spec.EcCwPerBlock);
            }

            List<byte> finalCw = new List<byte>(totalDataCw + spec.EcCwPerBlock * spec.Blocks);
            for (int i = 0; i < spec.DataCwPerBlock; i++) {
                for (int b = 0; b < spec.Blocks; b++) finalCw.Add(dBlocks[b][i]);
            }
            for (int i = 0; i < spec.EcCwPerBlock; i++) {
                for (int b = 0; b < spec.Blocks; b++) finalCw.Add(ecBlocks[b][i]);
            }

            int size = spec.Version * 4 + 17;
            bool[,] isFunc = new bool[size, size];
            bool[,] modules = new bool[size, size];
            DrawFunctionPatterns(modules, isFunc, size, spec.Version, spec.Align);

            // Place data bits in zig-zag
            int bitPos = 0;
            int totalBits = finalCw.Count * 8;
            for (int right = size - 1; right >= 1; right -= 2) {
                if (right == 6) right = 5;
                for (int vert = 0; vert < size; vert++) {
                    for (int j = 0; j < 2; j++) {
                        int x = right - j;
                        bool upward = ((right + 1) & 2) == 0;
                        int y = upward ? (size - 1 - vert) : vert;
                        if (!isFunc[y, x] && bitPos < totalBits) {
                            int cw = finalCw[bitPos >> 3];
                            bool bit = ((cw >> (7 - (bitPos & 7))) & 1) != 0;
                            modules[y, x] = bit;
                            bitPos++;
                        }
                    }
                }
            }

            // Pick best mask 0..7
            int bestMask = 0;
            int bestPenalty = int.MaxValue;
            for (int m = 0; m < 8; m++) {
                bool[,] cand = (bool[,])modules.Clone();
                ApplyMask(cand, isFunc, size, m);
                DrawFormatBits(cand, isFunc, size, spec.EcIndicator, m);
                int p = CalcPenalty(cand, size);
                if (p < bestPenalty) {
                    bestPenalty = p;
                    bestMask = m;
                }
            }
            ApplyMask(modules, isFunc, size, bestMask);
            DrawFormatBits(modules, isFunc, size, spec.EcIndicator, bestMask);

            int quiet = 4;
            int totalMod = size + quiet * 2;
            int scale = Math.Max(2, targetPixels / totalMod);
            int imgSize = totalMod * scale;
            Bitmap bmp = new Bitmap(imgSize, imgSize);
            using (Graphics g = Graphics.FromImage(bmp)) {
                g.Clear(Color.White);
                using (Brush dark = new SolidBrush(Color.FromArgb(18, 22, 20))) {
                    for (int y = 0; y < size; y++) {
                        for (int x = 0; x < size; x++) {
                            if (modules[y, x]) {
                                g.FillRectangle(dark, (x + quiet) * scale, (y + quiet) * scale, scale, scale);
                            }
                        }
                    }
                }
            }
            return bmp;
        }

        private static void AppendBits(List<int> bits, int val, int count) {
            for (int i = count - 1; i >= 0; i--) bits.Add((val >> i) & 1);
        }

        private static int GfMul(int x, int y) {
            int z = 0;
            for (int i = 7; i >= 0; i--) {
                z = (z << 1) ^ ((z >> 7) * 0x11D);
                z ^= ((y >> i) & 1) * x;
            }
            return z;
        }

        private static byte[] BuildRsGenerator(int deg) {
            byte[] res = new byte[deg];
            res[deg - 1] = 1;
            int root = 1;
            for (int i = 0; i < deg; i++) {
                for (int j = 0; j < deg; j++) {
                    res[j] = (byte)(GfMul(res[j], root) ^ (j + 1 < deg ? res[j + 1] : 0));
                }
                root = GfMul(root, 0x02);
            }
            return res;
        }

        private static byte[] ComputeRsEc(byte[] data, byte[] gen, int deg) {
            byte[] rem = new byte[deg];
            foreach (byte b in data) {
                int factor = b ^ rem[0];
                Array.Copy(rem, 1, rem, 0, deg - 1);
                rem[deg - 1] = 0;
                for (int j = 0; j < deg; j++) {
                    rem[j] ^= (byte)GfMul(gen[j], factor);
                }
            }
            return rem;
        }

        private static void SetFunc(bool[,] modules, bool[,] isFunc, int y, int x, bool val) {
            modules[y, x] = val;
            isFunc[y, x] = true;
        }

        private static void DrawFinder(bool[,] modules, bool[,] isFunc, int size, int cy, int cx) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int y = cy + dy, x = cx + dx;
                    if (y >= 0 && y < size && x >= 0 && x < size) {
                        int d = Math.Max(Math.Abs(dy), Math.Abs(dx));
                        SetFunc(modules, isFunc, y, x, d != 2 && d != 4);
                    }
                }
            }
        }

        private static void DrawAlign(bool[,] modules, bool[,] isFunc, int cy, int cx) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int d = Math.Max(Math.Abs(dy), Math.Abs(dx));
                    SetFunc(modules, isFunc, cy + dy, cx + dx, d != 1);
                }
            }
        }

        private static void DrawFunctionPatterns(bool[,] modules, bool[,] isFunc, int size, int version, int[] align) {
            for (int i = 0; i < size; i++) {
                SetFunc(modules, isFunc, 6, i, i % 2 == 0);
                SetFunc(modules, isFunc, i, 6, i % 2 == 0);
            }
            DrawFinder(modules, isFunc, size, 3, 3);
            DrawFinder(modules, isFunc, size, 3, size - 4);
            DrawFinder(modules, isFunc, size, size - 4, 3);

            int n = align.Length;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (!((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0))) {
                        DrawAlign(modules, isFunc, align[i], align[j]);
                    }
                }
            }
            // Reserve format modules
            for (int i = 0; i <= 8; i++) {
                if (i != 6) {
                    isFunc[8, i] = true;
                    isFunc[i, 8] = true;
                }
            }
            for (int i = size - 8; i < size; i++) {
                isFunc[8, i] = true;
                isFunc[i, 8] = true;
            }
            SetFunc(modules, isFunc, size - 8, 8, true);

            // Version Information (18-bit BCH) for QR Version >= 7
            if (version >= 7) {
                int rem = version;
                for (int i = 0; i < 12; i++) {
                    rem = (rem << 1) ^ ((rem >> 11) * 0x1F25);
                }
                int vBits = (version << 12) | rem;
                for (int i = 0; i < 6; i++) {
                    for (int j = 0; j < 3; j++) {
                        bool bit = ((vBits >> (i * 3 + j)) & 1) != 0;
                        SetFunc(modules, isFunc, i, size - 11 + j, bit);
                        SetFunc(modules, isFunc, size - 11 + j, i, bit);
                    }
                }
            }
        }

        private static void DrawFormatBits(bool[,] modules, bool[,] isFunc, int size, int ecInd, int mask) {
            int data = (ecInd << 3) | mask;
            int rem = data;
            for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >> 9) * 0x537);
            int bits = ((data << 10) | rem) ^ 0x5412;

            for (int i = 0; i <= 5; i++) modules[8, i] = ((bits >> i) & 1) != 0;
            modules[8, 7] = ((bits >> 6) & 1) != 0;
            modules[8, 8] = ((bits >> 7) & 1) != 0;
            modules[7, 8] = ((bits >> 8) & 1) != 0;
            for (int i = 9; i < 15; i++) modules[14 - i, 8] = ((bits >> i) & 1) != 0;

            for (int i = 0; i < 8; i++) modules[8, size - 1 - i] = ((bits >> i) & 1) != 0;
            for (int i = 8; i < 15; i++) modules[size - 15 + i, 8] = ((bits >> i) & 1) != 0;
            modules[size - 8, 8] = true;
        }

        private static void ApplyMask(bool[,] modules, bool[,] isFunc, int size, int mask) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (isFunc[y, x]) continue;
                    bool inv;
                    switch (mask) {
                        case 0: inv = (y + x) % 2 == 0; break;
                        case 1: inv = y % 2 == 0; break;
                        case 2: inv = x % 3 == 0; break;
                        case 3: inv = (y + x) % 3 == 0; break;
                        case 4: inv = (y / 2 + x / 3) % 2 == 0; break;
                        case 5: inv = ((y * x) % 2 + (y * x) % 3) == 0; break;
                        case 6: inv = (((y * x) % 2 + (y * x) % 3) % 2) == 0; break;
                        default: inv = (((y + x) % 2 + (y * x) % 3) % 2) == 0; break;
                    }
                    if (inv) modules[y, x] = !modules[y, x];
                }
            }
        }

        private static int CalcPenalty(bool[,] modules, int size) {
            int penalty = 0;
            for (int y = 0; y < size; y++) {
                int run = 1;
                for (int x = 1; x < size; x++) {
                    if (modules[y, x] == modules[y, x - 1]) run++;
                    else { if (run >= 5) penalty += 3 + (run - 5); run = 1; }
                }
                if (run >= 5) penalty += 3 + (run - 5);
            }
            for (int x = 0; x < size; x++) {
                int run = 1;
                for (int y = 1; y < size; y++) {
                    if (modules[y, x] == modules[y - 1, x]) run++;
                    else { if (run >= 5) penalty += 3 + (run - 5); run = 1; }
                }
                if (run >= 5) penalty += 3 + (run - 5);
            }
            for (int y = 0; y < size - 1; y++) {
                for (int x = 0; x < size - 1; x++) {
                    bool c = modules[y, x];
                    if (modules[y, x + 1] == c && modules[y + 1, x] == c && modules[y + 1, x + 1] == c) penalty += 3;
                }
            }
            return penalty;
        }
    }
}
'@

if (-not ("KagojpotroServerEngine.KagojpotroTcpServer" -as [type])) {
    Add-Type -TypeDefinition $CSharpServerCode -ReferencedAssemblies "System.Web.Extensions","System.Drawing"
}

$script:Server = New-Object KagojpotroServerEngine.KagojpotroTcpServer($Config.StoragePath, $Config.Port)

$LogoBitmap = New-KagojpotroLogoBitmap 32
$AppIcon = [System.Drawing.Icon]::FromHandle($LogoBitmap.GetHicon())

# Build Minimal Windows Forms Window ("কাগজপত্র", QR Code, Folder Icon + Start/Stop Toggle + Red/Yellow/Green Dot)
$Form = New-Object System.Windows.Forms.Form
$Form.Text = $WindowTitle
$Form.Icon = $AppIcon
$Form.ClientSize = New-Object System.Drawing.Size(296, 344)
$Form.StartPosition = "CenterScreen"
$Form.FormBorderStyle = "FixedSingle"
$Form.MaximizeBox = $false
$Form.MinimizeBox = $true
$Form.TopMost = $false
$Form.BackColor = [System.Drawing.Color]::FromArgb(19, 22, 21)
$Form.ForeColor = [System.Drawing.Color]::White

$FontTitle = New-Object System.Drawing.Font("Nirmala UI", 16, [System.Drawing.FontStyle]::Bold)
$FontLabel = New-Object System.Drawing.Font("Segoe UI", 9.5, [System.Drawing.FontStyle]::Bold)
$FontSmall = New-Object System.Drawing.Font("Segoe UI", 8.5, [System.Drawing.FontStyle]::Regular)

$ToolTip = New-Object System.Windows.Forms.ToolTip

# 1. Centered "কাগজপত্র" Header inside the window
$LblHeader = New-Object System.Windows.Forms.Label
$LblHeader.Text = $BanglaTitle
$LblHeader.Font = $FontTitle
$LblHeader.ForeColor = [System.Drawing.Color]::White
$LblHeader.TextAlign = [System.Drawing.ContentAlignment]::MiddleCenter
$LblHeader.Location = New-Object System.Drawing.Point(0, 12)
$LblHeader.Size = New-Object System.Drawing.Size(296, 32)
$Form.Controls.Add($LblHeader)

# 2. Central QR Code Card
$PicQr = New-Object System.Windows.Forms.PictureBox
$PicQr.Location = New-Object System.Drawing.Point(40, 52)
$PicQr.Size = New-Object System.Drawing.Size(216, 216)
$PicQr.SizeMode = [System.Windows.Forms.PictureBoxSizeMode]::Zoom
$PicQr.BackColor = [System.Drawing.Color]::FromArgb(30, 35, 34)
$PicQr.BorderStyle = [System.Windows.Forms.BorderStyle]::FixedSingle
$Form.Controls.Add($PicQr)

$LblQrWait = New-Object System.Windows.Forms.Label
$LblQrWait.Text = "Generating QR..."
$LblQrWait.Font = $FontSmall
$LblQrWait.ForeColor = [System.Drawing.Color]::FromArgb(156, 163, 175)
$LblQrWait.BackColor = [System.Drawing.Color]::FromArgb(30, 35, 34)
$LblQrWait.TextAlign = [System.Drawing.ContentAlignment]::MiddleCenter
$LblQrWait.Location = New-Object System.Drawing.Point(48, 146)
$LblQrWait.Size = New-Object System.Drawing.Size(200, 28)
$Form.Controls.Add($LblQrWait)
$LblQrWait.BringToFront()

# 3. Bottom Control Row: [ Small Folder Icon Button ] -> [ Start / Stop Server Toggle ] -> [ Red / Yellow / Green Dot ]
$OpenSyncFolderAction = {
    if ($Config.StoragePath -and (Test-Path $Config.StoragePath)) {
        Start-Process explorer.exe $Config.StoragePath
    }
}

$ChangeSyncFolderAction = {
    $newDir = Select-StorageDirectory $Config.StoragePath
    if ($newDir) {
        $Config.StoragePath = $newDir
        $script:Server.StorageRoot = $newDir
        Save-ServerConfig
        $ToolTip.SetToolTip($BtnFolderMenu, "Sync Folder: $($Config.StoragePath)`nClick to Open or Change folder")
    }
}

$FolderContextMenu = New-Object System.Windows.Forms.ContextMenuStrip
$MenuOpenSyncFolder = $FolderContextMenu.Items.Add("Open Sync Folder")
$MenuOpenSyncFolder.Add_Click($OpenSyncFolderAction)
$MenuChangeSyncFolder = $FolderContextMenu.Items.Add("Change Sync Folder...")
$MenuChangeSyncFolder.Add_Click($ChangeSyncFolderAction)

$BtnFolderMenu = New-Object System.Windows.Forms.Button
$BtnFolderMenu.Image = (New-FolderIconBitmap 20)
$BtnFolderMenu.ImageAlign = [System.Drawing.ContentAlignment]::MiddleCenter
$BtnFolderMenu.Location = New-Object System.Drawing.Point(40, 286)
$BtnFolderMenu.Size = New-Object System.Drawing.Size(38, 36)
$BtnFolderMenu.BackColor = [System.Drawing.Color]::FromArgb(38, 44, 42)
$BtnFolderMenu.FlatStyle = "Flat"
$BtnFolderMenu.FlatAppearance.BorderColor = [System.Drawing.Color]::FromArgb(62, 72, 69)
$BtnFolderMenu.FlatAppearance.BorderSize = 1
$BtnFolderMenu.Add_Click({
    $FolderContextMenu.Show($BtnFolderMenu, (New-Object System.Drawing.Point(0, $BtnFolderMenu.Height)))
})
$Form.Controls.Add($BtnFolderMenu)
$ToolTip.SetToolTip($BtnFolderMenu, "Sync Folder: $($Config.StoragePath)`nClick to Open or Change folder")

$BtnToggleServer = New-Object System.Windows.Forms.Button
$BtnToggleServer.Text = "Stop Server"
$BtnToggleServer.Font = $FontLabel
$BtnToggleServer.Location = New-Object System.Drawing.Point(86, 286)
$BtnToggleServer.Size = New-Object System.Drawing.Size(136, 36)
$BtnToggleServer.BackColor = [System.Drawing.Color]::FromArgb(153, 45, 53)
$BtnToggleServer.ForeColor = [System.Drawing.Color]::White
$BtnToggleServer.FlatStyle = "Flat"
$BtnToggleServer.FlatAppearance.BorderSize = 0
$Form.Controls.Add($BtnToggleServer)

$DotRedBitmap = New-StatusDotBitmap ([System.Drawing.Color]::FromArgb(239, 68, 68)) 20
$DotYellowBitmap = New-StatusDotBitmap ([System.Drawing.Color]::FromArgb(245, 158, 11)) 20
$DotGreenBitmap = New-StatusDotBitmap ([System.Drawing.Color]::FromArgb(34, 197, 94)) 20

$PicStatusDot = New-Object System.Windows.Forms.PictureBox
$PicStatusDot.Location = New-Object System.Drawing.Point(234, 294)
$PicStatusDot.Size = New-Object System.Drawing.Size(20, 20)
$PicStatusDot.SizeMode = [System.Windows.Forms.PictureBoxSizeMode]::CenterImage
$PicStatusDot.BackColor = [System.Drawing.Color]::Transparent
$PicStatusDot.Image = $DotYellowBitmap
$Form.Controls.Add($PicStatusDot)

$script:CurrentDotState = ""
function Update-StatusIndicatorDot {
    if (-not $script:Server.IsRunning) {
        if ($script:CurrentDotState -ne "RED") {
            $script:CurrentDotState = "RED"
            $PicStatusDot.Image = $DotRedBitmap
            $ToolTip.SetToolTip($PicStatusDot, "Red: Server Off")
        }
        return
    }
    $lastAct = $script:Server.LastClientActivityUtc
    $isPhoneSynced = ($lastAct -gt [DateTime]::MinValue) -and ((([DateTime]::UtcNow - $lastAct).TotalSeconds) -le 180)
    if ($isPhoneSynced) {
        if ($script:CurrentDotState -ne "GREEN") {
            $script:CurrentDotState = "GREEN"
            $PicStatusDot.Image = $DotGreenBitmap
            $ToolTip.SetToolTip($PicStatusDot, "Green: Server Running & Phone Synced")
        }
    } else {
        if ($script:CurrentDotState -ne "YELLOW") {
            $script:CurrentDotState = "YELLOW"
            $PicStatusDot.Image = $DotYellowBitmap
            $ToolTip.SetToolTip($PicStatusDot, "Yellow: Server Running (Waiting for Phone Sync)")
        }
    }
}

function Update-QrPreview([string]$urlText, [string]$placeholderText = "Waiting for QR...") {
    try {
        if ($PicQr.Image) {
            $old = $PicQr.Image
            $PicQr.Image = $null
            $old.Dispose()
        }
        if ($urlText -and $urlText -like "http*") {
            $bmp = [KagojpotroServerEngine.SimpleQrGenerator]::RenderUrlQrBitmap($urlText, 432)
            $PicQr.BackColor = [System.Drawing.Color]::White
            $PicQr.Image = $bmp
            $LblQrWait.Visible = $false
            $ToolTip.SetToolTip($PicQr, "Scan from phone to connect")
        } else {
            $PicQr.BackColor = [System.Drawing.Color]::FromArgb(30, 35, 34)
            $LblQrWait.Text = $placeholderText
            $LblQrWait.Visible = $true
            $ToolTip.SetToolTip($PicQr, $placeholderText)
        }
    } catch {}
}

function Get-BestLanIpAddress {
    try {
        foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
            if ($nic.OperationalStatus -ne [System.Net.NetworkInformation.OperationalStatus]::Up) { continue }
            if ($nic.NetworkInterfaceType -eq [System.Net.NetworkInformation.NetworkInterfaceType]::Loopback) { continue }
            if ($nic.Description -match "Virtual|Hyper-V|WSL|VMware|VirtualBox|Loopback") { continue }
            $ipProps = $nic.GetIPProperties()
            foreach ($uni in $ipProps.UnicastAddresses) {
                if ($uni.Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork) {
                    $ip = $uni.Address.ToString()
                    if (-not $ip.StartsWith("127.") -and -not $ip.StartsWith("169.254.")) {
                        return $ip
                    }
                }
            }
        }
    } catch {}
    return "127.0.0.1"
}

# System Tray Icon: Uses Application::Run($Form) so minimizing hides the window without closing the process
$TrayIcon = New-Object System.Windows.Forms.NotifyIcon
$TrayIcon.Icon = $AppIcon
$TrayIcon.Text = $WindowTitle
$TrayIcon.Visible = $true

$RestoreWindowAction = {
    $Form.ShowInTaskbar = $true
    $Form.Show()
    $Form.WindowState = [System.Windows.Forms.FormWindowState]::Normal
    $Form.Activate()
}

$TrayMenu = New-Object System.Windows.Forms.ContextMenuStrip
$MenuOpen = $TrayMenu.Items.Add("Open $BanglaTitle")
$MenuOpen.Add_Click($RestoreWindowAction)
$MenuTrayOpenFolder = $TrayMenu.Items.Add("Open Sync Folder")
$MenuTrayOpenFolder.Add_Click($OpenSyncFolderAction)
$MenuTrayChangeFolder = $TrayMenu.Items.Add("Change Sync Folder...")
$MenuTrayChangeFolder.Add_Click($ChangeSyncFolderAction)
$TrayMenu.Items.Add("-") | Out-Null
$MenuExit = $TrayMenu.Items.Add("Close Server")
$MenuExit.Add_Click({
    $Form.Close()
})
$TrayIcon.ContextMenuStrip = $TrayMenu

$TrayIcon.Add_MouseClick({
    param($sender, $e)
    if ($e.Button -eq [System.Windows.Forms.MouseButtons]::Left) {
        & $RestoreWindowAction
    }
})

$Form.Add_Resize({
    if ($Form.WindowState -eq [System.Windows.Forms.FormWindowState]::Minimized) {
        $Form.ShowInTaskbar = $false
        $Form.Hide()
        $TrayIcon.Visible = $true
        try {
            $TrayIcon.ShowBalloonTip(1800, $BanglaTitle, "Server is running in System Tray. Click icon to open.", [System.Windows.Forms.ToolTipIcon]::Info)
        } catch {}
    }
})

$script:TunnelProcess = $null
$script:WebClient = $null
$script:SessionVersion = 0
$script:TunnelWaitTicks = 0
$script:DownloadStallTicks = 0
$script:LastDownloadBytes = 0L
$script:IsDownloadingCloudflared = $false
$script:CloudflaredTemp = Join-Path $AppDataDir "cloudflared.exe.tmp"
$script:LocalFallbackUrl = ""
$script:CurrentTunnelLog = $TunnelLogFile

function Stop-CloudflaredProcess {
    if ($script:TunnelProcess) {
        try {
            $tPid = $script:TunnelProcess.Id
            if (-not $script:TunnelProcess.HasExited) {
                try { $script:TunnelProcess.Kill() } catch {}
                try { Start-Process -FilePath "taskkill.exe" -ArgumentList "/T /F /PID $tPid" -WindowStyle Hidden -Wait -ErrorAction SilentlyContinue } catch {}
                try { $script:TunnelProcess.WaitForExit(1500) } catch {}
            }
            $script:TunnelProcess.Dispose()
        } catch {}
    }
    $script:TunnelProcess = $null
}

function Launch-CloudflaredProcess {
    try {
        Stop-CloudflaredProcess
        Get-ChildItem -Path $AppDataDir -Filter "tunnel*.log" -ErrorAction SilentlyContinue | ForEach-Object {
            Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
        }
        $script:CurrentTunnelLog = Join-Path $AppDataDir "tunnel_$($PID)_$($script:SessionVersion).log"
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $CloudflaredExe
        $psi.Arguments = "tunnel --no-autoupdate --protocol http2 --url http://127.0.0.1:$($Config.Port) --logfile `"$($script:CurrentTunnelLog)`""
        $psi.CreateNoWindow = $true
        $psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
        $psi.UseShellExecute = $false
        $script:TunnelProcess = [System.Diagnostics.Process]::Start($psi)
    } catch {}
}

$Timer = New-Object System.Windows.Forms.Timer
$Timer.Interval = 500

$StatusDotTimer = New-Object System.Windows.Forms.Timer
$StatusDotTimer.Interval = 700
$StatusDotTimer.Add_Tick({
    Update-StatusIndicatorDot
})
$StatusDotTimer.Start()

$Timer.Add_Tick({
    if (-not $script:Server.IsRunning) {
        $Timer.Stop()
        return
    }
    if ($script:IsDownloadingCloudflared) {
        $curBytes = 0L
        if (Test-Path -LiteralPath $script:CloudflaredTemp) {
            try { $curBytes = (Get-Item -LiteralPath $script:CloudflaredTemp).Length } catch {}
        }
        if ($curBytes -gt $script:LastDownloadBytes) {
            $script:LastDownloadBytes = $curBytes
            $script:DownloadStallTicks = 0
        } else {
            $script:DownloadStallTicks++
        }
        # If download makes zero progress for ~7 seconds, show LAN QR immediately so user is never stuck at 0%
        if ($script:DownloadStallTicks -ge 14 -and $script:LocalFallbackUrl) {
            $script:IsDownloadingCloudflared = $false
            try { if ($script:WebClient) { $script:WebClient.CancelAsync() } } catch {}
            $Timer.Stop()
            Update-QrPreview $script:LocalFallbackUrl
            Update-StatusIndicatorDot
        }
        return
    }
    $script:TunnelWaitTicks++
    if ($script:CurrentTunnelLog -and (Test-Path $script:CurrentTunnelLog)) {
        try {
            $logContent = Get-Content $script:CurrentTunnelLog -Raw -ErrorAction SilentlyContinue
            if ($logContent -match "(https://[a-zA-Z0-9-]+\.trycloudflare\.com)") {
                $pubUrl = $Matches[1]
                $Timer.Stop()
                Update-QrPreview $pubUrl
                Update-StatusIndicatorDot
                return
            }
        } catch {}
    }
    # If PC has no internet or tunnel fails to initialize after ~8 seconds, fall back to LAN QR code
    if ($script:TunnelWaitTicks -ge 16 -and $script:LocalFallbackUrl) {
        $Timer.Stop()
        Update-QrPreview $script:LocalFallbackUrl
        Update-StatusIndicatorDot
    }
})

function Stop-AllServerComponents {
    $script:SessionVersion++
    $script:IsDownloadingCloudflared = $false
    $Timer.Stop()
    if ($script:WebClient) {
        try { $script:WebClient.CancelAsync() } catch {}
    }
    $script:Server.Stop()
    Stop-CloudflaredProcess

    $BtnToggleServer.Text = "Start Server"
    $BtnToggleServer.BackColor = [System.Drawing.Color]::FromArgb(15, 139, 109)
    Update-QrPreview "" "Server Off"
    Update-StatusIndicatorDot
}

function Start-AllServerComponents {
    $script:SessionVersion++
    $script:TunnelWaitTicks = 0
    $script:DownloadStallTicks = 0
    $script:LastDownloadBytes = 0L
    $script:IsDownloadingCloudflared = $false
    $Timer.Stop()

    $script:Server.StorageRoot = $Config.StoragePath
    $script:Server.Port = $Config.Port
    try {
        $script:Server.Start()
    } catch {
        $Config.Port = Get-Random -Minimum 8086 -Maximum 8999
        Save-ServerConfig
        $script:Server.Port = $Config.Port
        $script:Server.Start()
    }

    $lanIp = Get-BestLanIpAddress
    $script:LocalFallbackUrl = "http://${lanIp}:$($Config.Port)"

    $BtnToggleServer.Text = "Stop Server"
    $BtnToggleServer.BackColor = [System.Drawing.Color]::FromArgb(153, 45, 53)
    Update-StatusIndicatorDot

    Update-QrPreview "" "Generating QR..."

    $isExeValid = (Test-Path -LiteralPath $CloudflaredExe) -and ((Get-Item -LiteralPath $CloudflaredExe).Length -gt 15000000)

    if ($isExeValid) {
        try { Unblock-File -LiteralPath $CloudflaredExe -ErrorAction SilentlyContinue } catch {}
        Launch-CloudflaredProcess
        $Timer.Start()
    } else {
        if (Test-Path -LiteralPath $script:CloudflaredTemp) {
            Remove-Item -LiteralPath $script:CloudflaredTemp -Force -ErrorAction SilentlyContinue
        }
        Update-QrPreview "" "Preparing QR (0%)..."
        $script:IsDownloadingCloudflared = $true
        $Timer.Start()
        try {
            $dlUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
            if ($script:WebClient) {
                try { $script:WebClient.CancelAsync(); $script:WebClient.Dispose() } catch {}
            }
            $script:WebClient = New-Object System.Net.WebClient
            $script:WebClient.Headers.Add("User-Agent", "Mozilla/5.0 KagojpotroServer/1.0")
            $script:WebClient.Add_DownloadProgressChanged({
                param($sender, $e)
                if (-not $script:Server.IsRunning -or -not $script:IsDownloadingCloudflared) { return }
                $script:DownloadStallTicks = 0
                $LblQrWait.Text = "Preparing QR ($($e.ProgressPercentage)%)..."
            })
            $script:WebClient.Add_DownloadFileCompleted({
                param($sender, $e)
                if (-not $script:Server.IsRunning) { return }
                $script:IsDownloadingCloudflared = $false
                if ($null -eq $e.Error -and -not $e.Cancelled -and (Test-Path -LiteralPath $script:CloudflaredTemp) -and ((Get-Item -LiteralPath $script:CloudflaredTemp).Length -gt 15000000)) {
                    try {
                        Move-Item -LiteralPath $script:CloudflaredTemp -Destination $CloudflaredExe -Force
                        Unblock-File -LiteralPath $CloudflaredExe -ErrorAction SilentlyContinue
                    } catch {}
                    Update-QrPreview "" "Generating QR..."
                    $script:TunnelWaitTicks = 0
                    Launch-CloudflaredProcess
                    $Timer.Start()
                } else {
                    Remove-Item -LiteralPath $script:CloudflaredTemp -Force -ErrorAction SilentlyContinue
                    $Timer.Stop()
                    Update-QrPreview $script:LocalFallbackUrl
                    Update-StatusIndicatorDot
                }
            })
            $script:WebClient.DownloadFileAsync((New-Object System.Uri($dlUrl)), $script:CloudflaredTemp)
        } catch {
            $script:IsDownloadingCloudflared = $false
            $Timer.Stop()
            Update-QrPreview $script:LocalFallbackUrl
            Update-StatusIndicatorDot
        }
    }
}

$BtnToggleServer.Add_Click({
    if ($script:Server.IsRunning) {
        Stop-AllServerComponents
    } else {
        Start-AllServerComponents
    }
})

$Form.Add_Shown({
    $Form.Activate()
    Start-AllServerComponents
})

# Closing the window stops the server and exits completely
$Form.Add_FormClosing({
    $script:SessionVersion++
    $Timer.Stop()
    $StatusDotTimer.Stop()
    $TrayIcon.Visible = $false
    $TrayIcon.Dispose()
    $script:Server.Stop()
    Stop-CloudflaredProcess
    try {
        if ($AppIcon) {
            [KagojpotroWin32.Win32HideConsole]::DestroyIcon($AppIcon.Handle) | Out-Null
            $AppIcon.Dispose()
        }
    } catch {}
    try { if ($LogoBitmap) { $LogoBitmap.Dispose() } } catch {}
    try { if ($DotRedBitmap) { $DotRedBitmap.Dispose() } } catch {}
    try { if ($DotYellowBitmap) { $DotYellowBitmap.Dispose() } } catch {}
    try { if ($DotGreenBitmap) { $DotGreenBitmap.Dispose() } } catch {}
    try {
        if ($script:InstanceMutex -and $script:InstanceMutexCreated) {
            $script:InstanceMutex.ReleaseMutex()
            $script:InstanceMutex.Dispose()
        }
    } catch {}
})

[System.Windows.Forms.Application]::Run($Form)
