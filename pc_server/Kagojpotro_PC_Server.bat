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

# Belt-and-suspenders: hide any console window if this .ps1 is launched directly
try {
    Add-Type -Name Win32HideConsole -Namespace KagojpotroWin32 -MemberDefinition '
        [System.Runtime.InteropServices.DllImport("kernel32.dll")]
        public static extern IntPtr GetConsoleWindow();
        [System.Runtime.InteropServices.DllImport("user32.dll")]
        public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
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

function New-ReloadIconBitmap([int]$size = 18) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::White, 2.0)
    $rect = New-Object System.Drawing.Rectangle(3, 3, ($size - 6), ($size - 6))
    $g.DrawArc($pen, $rect, -45, 285)

    $brush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $pts = @(
        (New-Object System.Drawing.Point(($size - 3), 2)),
        (New-Object System.Drawing.Point(($size - 3), 8)),
        (New-Object System.Drawing.Point(($size - 9), 5))
    )
    $g.FillPolygon($brush, $pts)

    $brush.Dispose()
    $pen.Dispose()
    $g.Dispose()
    return $bmp
}

# Background worker script for reliable, non-blocking multi-provider URL shortening
$script:ShortenWorkerScript = {
    param([string]$longUrl)
    if (-not $longUrl) { return $longUrl }

    [Net.ServicePointManager]::Expect100Continue = $false
    try {
        [Net.ServicePointManager]::SecurityProtocol = 3072 -bor 12288
    } catch {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    }

    function Invoke-HttpGetText([string]$apiUrl, [int]$timeoutMs = 5000) {
        try {
            $req = [System.Net.HttpWebRequest]::Create($apiUrl)
            $req.Method = "GET"
            $req.Timeout = $timeoutMs
            $req.ReadWriteTimeout = $timeoutMs
            $req.UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Kagojpotro/1.0"
            $req.Accept = "text/plain,application/json,*/*"
            $resp = $req.GetResponse()
            $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
            $txt = $sr.ReadToEnd().Trim()
            $sr.Close()
            $resp.Close()
            return $txt
        } catch {
            return $null
        }
    }

    function Invoke-HttpPostForm([string]$apiUrl, [string]$bodyText, [int]$timeoutMs = 5000) {
        try {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($bodyText)
            $req = [System.Net.HttpWebRequest]::Create($apiUrl)
            $req.Method = "POST"
            $req.Timeout = $timeoutMs
            $req.ReadWriteTimeout = $timeoutMs
            $req.UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Kagojpotro/1.0"
            $req.Accept = "application/json,text/plain,*/*"
            $req.ContentType = "application/x-www-form-urlencoded"
            $req.ContentLength = $bytes.Length
            $rs = $req.GetRequestStream()
            $rs.Write($bytes, 0, $bytes.Length)
            $rs.Close()
            $resp = $req.GetResponse()
            $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
            $txt = $sr.ReadToEnd().Trim()
            $sr.Close()
            $resp.Close()
            return $txt
        } catch {
            return $null
        }
    }

    $encoded = [System.Uri]::EscapeDataString($longUrl)

    # 1. TinyURL (reliable with trycloudflare.com)
    $s1 = Invoke-HttpGetText "https://tinyurl.com/api-create.php?url=$encoded"
    if ($s1 -and $s1 -match "^https?://tinyurl\.com/[a-zA-Z0-9_-]+/?$") {
        return ($s1 -replace "^http://", "https://").TrimEnd('/')
    }

    # 2. da.gd
    $s2 = Invoke-HttpGetText "https://da.gd/s?url=$encoded"
    if ($s2 -and $s2 -match "^https?://da\.gd/[a-zA-Z0-9_-]+/?$") {
        return ($s2 -replace "^http://", "https://").TrimEnd('/')
    }

    # 3. clck.ru
    $s3 = Invoke-HttpGetText "https://clck.ru/--?url=$encoded"
    if ($s3 -and $s3 -match "^https?://clck\.ru/[a-zA-Z0-9_-]+/?$") {
        return ($s3 -replace "^http://", "https://").TrimEnd('/')
    }

    # 4. spoo.me
    $s4Raw = Invoke-HttpPostForm "https://spoo.me/" "url=$encoded"
    if ($s4Raw) {
        try {
            $j4 = $s4Raw | ConvertFrom-Json
            if ($j4.short_url -and $j4.short_url -match "^https?://spoo\.me/[a-zA-Z0-9_-]+/?$") {
                return ($j4.short_url -replace "^http://", "https://").TrimEnd('/')
            }
        } catch {}
    }

    # 5. v.gd
    $s5 = Invoke-HttpGetText "https://v.gd/create.php?format=simple&url=$encoded"
    if ($s5 -and $s5 -match "^https?://v\.gd/[a-zA-Z0-9_-]+/?$") {
        return ($s5 -replace "^http://", "https://").TrimEnd('/')
    }

    # 6. is.gd fallback
    $s6 = Invoke-HttpGetText "https://is.gd/create.php?format=simple&url=$encoded"
    if ($s6 -and $s6 -match "^https?://is\.gd/[a-zA-Z0-9_-]+/?$") {
        return ($s6 -replace "^http://", "https://").TrimEnd('/')
    }

    return $longUrl
}

# Compile the embedded zero-admin C# HTTP Server engine (.NET TcpListener)
$CSharpServerCode = @'
using System;
using System.Collections.Generic;
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
        private readonly object _syncLock = new object();
        public string StorageRoot { get; set; }
        public int Port { get; set; }
        public bool IsRunning { get { return _running; } }

        public KagojpotroTcpServer(string storageRoot, int port) {
            StorageRoot = storageRoot;
            Port = port;
        }

        public void Start() {
            if (_running) return;
            _listener = new TcpListener(IPAddress.Any, Port);
            _listener.Start();
            _running = true;
            _acceptThread = new Thread(AcceptLoop) { IsBackground = true };
            _acceptThread.Start();
        }

        public void Stop() {
            _running = false;
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
                try {
                    NetworkStream stream = client.GetStream();
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

                    if (path.Equals("/api/ping", StringComparison.OrdinalIgnoreCase)) {
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
                            if (File.Exists(manifestPath)) {
                                lock (_syncLock) {
                                    try {
                                        if (File.Exists(manifestPath)) {
                                            string mStr = File.ReadAllText(manifestPath, Encoding.UTF8);
                                            MirrorHumanReadableFolders(uRoot, mStr, json);
                                        }
                                    } catch {}
                                }
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
                } catch {}
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

                string visibleRoot = Path.Combine(uRoot, "Folders");
                Directory.CreateDirectory(visibleRoot);

                HashSet<string> expectedDirs = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                expectedDirs.Add(Path.GetFullPath(visibleRoot));
                foreach (var kv in folderMap) {
                    string rel = BuildFolderRelativePath(kv.Key, folderMap);
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

                        string relFolder = BuildFolderRelativePath(folderId, folderMap);
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

        private string BuildFolderRelativePath(string folderId, Dictionary<string, Dictionary<string, object>> map) {
            if (string.IsNullOrEmpty(folderId) || !map.ContainsKey(folderId)) return "";
            List<string> segments = new List<string>();
            HashSet<string> visited = new HashSet<string>();
            string curr = folderId;
            while (!string.IsNullOrEmpty(curr) && map.ContainsKey(curr) && visited.Add(curr)) {
                var f = map[curr];
                string name = f.ContainsKey("name") ? SanitizeSegment(Convert.ToString(f["name"])) : "Folder";
                segments.Add(name);
                curr = (f.ContainsKey("parentId") && f["parentId"] != null) ? Convert.ToString(f["parentId"]) : null;
            }
            segments.Reverse();
            return string.Join(Path.DirectorySeparatorChar.ToString(), segments.ToArray());
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
            string statusText = status == 200 ? "OK" : "Error";
            string header = "HTTP/1.1 " + status + " " + statusText + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + body.Length + "\r\nConnection: close\r\n\r\n";
            byte[] hBytes = Encoding.ASCII.GetBytes(header);
            stream.Write(hBytes, 0, hBytes.Length);
            stream.Write(body, 0, body.Length);
            stream.Flush();
        }
    }
}
'@

if (-not ("KagojpotroServerEngine.KagojpotroTcpServer" -as [type])) {
    Add-Type -TypeDefinition $CSharpServerCode -ReferencedAssemblies "System.Web.Extensions"
}

$script:Server = New-Object KagojpotroServerEngine.KagojpotroTcpServer($Config.StoragePath, $Config.Port)

$LogoBitmap = New-KagojpotroLogoBitmap 32
$AppIcon = [System.Drawing.Icon]::FromHandle($LogoBitmap.GetHicon())

# Build Minimal Windows Forms Window
$Form = New-Object System.Windows.Forms.Form
$Form.Text = $WindowTitle
$Form.Icon = $AppIcon
$Form.Size = New-Object System.Drawing.Size(510, 262)
$Form.StartPosition = "CenterScreen"
$Form.FormBorderStyle = "FixedSingle"
$Form.MaximizeBox = $false
$Form.MinimizeBox = $true
$Form.TopMost = $false
$Form.BackColor = [System.Drawing.Color]::FromArgb(19, 22, 21)
$Form.ForeColor = [System.Drawing.Color]::White

$FontTitle = New-Object System.Drawing.Font("Nirmala UI", 14, [System.Drawing.FontStyle]::Bold)
$FontLabel = New-Object System.Drawing.Font("Segoe UI", 9.5, [System.Drawing.FontStyle]::Bold)
$FontValue = New-Object System.Drawing.Font("Consolas", 10, [System.Drawing.FontStyle]::Bold)
$FontSmall = New-Object System.Drawing.Font("Segoe UI", 8.5, [System.Drawing.FontStyle]::Regular)

# Centered "কাগজপত্র" Header inside the window
$LblHeader = New-Object System.Windows.Forms.Label
$LblHeader.Text = $BanglaTitle
$LblHeader.Font = $FontTitle
$LblHeader.ForeColor = [System.Drawing.Color]::White
$LblHeader.TextAlign = [System.Drawing.ContentAlignment]::MiddleCenter
$LblHeader.Location = New-Object System.Drawing.Point(0, 10)
$LblHeader.Size = New-Object System.Drawing.Size(494, 30)
$Form.Controls.Add($LblHeader)

# 1. Storage Folder
$LblStorageTitle = New-Object System.Windows.Forms.Label
$LblStorageTitle.Text = "Storage Folder:"
$LblStorageTitle.Font = $FontLabel
$LblStorageTitle.Location = New-Object System.Drawing.Point(20, 46)
$LblStorageTitle.Size = New-Object System.Drawing.Size(200, 18)
$Form.Controls.Add($LblStorageTitle)

$TxtStorage = New-Object System.Windows.Forms.TextBox
$TxtStorage.Text = $Config.StoragePath
$TxtStorage.Font = $FontSmall
$TxtStorage.ReadOnly = $true
$TxtStorage.BackColor = [System.Drawing.Color]::FromArgb(30, 35, 34)
$TxtStorage.ForeColor = [System.Drawing.Color]::White
$TxtStorage.Location = New-Object System.Drawing.Point(20, 66)
$TxtStorage.Size = New-Object System.Drawing.Size(300, 24)
$Form.Controls.Add($TxtStorage)

$BtnOpenFolder = New-Object System.Windows.Forms.Button
$BtnOpenFolder.Text = "Open"
$BtnOpenFolder.Location = New-Object System.Drawing.Point(326, 64)
$BtnOpenFolder.Size = New-Object System.Drawing.Size(72, 27)
$BtnOpenFolder.BackColor = [System.Drawing.Color]::FromArgb(42, 48, 46)
$BtnOpenFolder.FlatStyle = "Flat"
$BtnOpenFolder.Add_Click({
    if (Test-Path $Config.StoragePath) {
        Start-Process explorer.exe $Config.StoragePath
    }
})
$Form.Controls.Add($BtnOpenFolder)

$BtnChangeFolder = New-Object System.Windows.Forms.Button
$BtnChangeFolder.Text = "Change"
$BtnChangeFolder.Location = New-Object System.Drawing.Point(404, 64)
$BtnChangeFolder.Size = New-Object System.Drawing.Size(74, 27)
$BtnChangeFolder.BackColor = [System.Drawing.Color]::FromArgb(42, 48, 46)
$BtnChangeFolder.FlatStyle = "Flat"
$BtnChangeFolder.Add_Click({
    $newDir = Select-StorageDirectory $Config.StoragePath
    if ($newDir) {
        $Config.StoragePath = $newDir
        $script:Server.StorageRoot = $newDir
        $TxtStorage.Text = $newDir
        Save-ServerConfig
    }
})
$Form.Controls.Add($BtnChangeFolder)

# 2. Internet Server URL + Reload Icon Button + Copy URL Button
$LblUrlTitle = New-Object System.Windows.Forms.Label
$LblUrlTitle.Text = "Internet Server URL:"
$LblUrlTitle.Font = $FontLabel
$LblUrlTitle.Location = New-Object System.Drawing.Point(20, 102)
$LblUrlTitle.Size = New-Object System.Drawing.Size(300, 18)
$Form.Controls.Add($LblUrlTitle)

$TxtUrl = New-Object System.Windows.Forms.TextBox
$TxtUrl.Text = "Starting..."
$TxtUrl.Font = $FontValue
$TxtUrl.ReadOnly = $true
$TxtUrl.BackColor = [System.Drawing.Color]::FromArgb(30, 35, 34)
$TxtUrl.ForeColor = [System.Drawing.Color]::FromArgb(16, 185, 129)
$TxtUrl.Location = New-Object System.Drawing.Point(20, 122)
$TxtUrl.Size = New-Object System.Drawing.Size(340, 26)
$Form.Controls.Add($TxtUrl)

$BtnReloadUrl = New-Object System.Windows.Forms.Button
$BtnReloadUrl.Image = (New-ReloadIconBitmap 18)
$BtnReloadUrl.ImageAlign = [System.Drawing.ContentAlignment]::MiddleCenter
$BtnReloadUrl.Location = New-Object System.Drawing.Point(366, 121)
$BtnReloadUrl.Size = New-Object System.Drawing.Size(32, 28)
$BtnReloadUrl.BackColor = [System.Drawing.Color]::FromArgb(42, 48, 46)
$BtnReloadUrl.FlatStyle = "Flat"
$Form.Controls.Add($BtnReloadUrl)

$ToolTip = New-Object System.Windows.Forms.ToolTip
$ToolTip.SetToolTip($BtnReloadUrl, "Regenerate & shorten new URL")

$BtnCopyUrl = New-Object System.Windows.Forms.Button
$BtnCopyUrl.Text = "Copy URL"
$BtnCopyUrl.Location = New-Object System.Drawing.Point(404, 121)
$BtnCopyUrl.Size = New-Object System.Drawing.Size(74, 28)
$BtnCopyUrl.BackColor = [System.Drawing.Color]::FromArgb(15, 139, 109)
$BtnCopyUrl.FlatStyle = "Flat"
$BtnCopyUrl.Enabled = $false
$BtnCopyUrl.Add_Click({
    if ($TxtUrl.Text -and $TxtUrl.Text -like "http*") {
        [System.Windows.Forms.Clipboard]::SetText($TxtUrl.Text)
        $BtnCopyUrl.Text = "Copied!"
    }
})
$Form.Controls.Add($BtnCopyUrl)

# 3. Server Control Row under Internet Server URL Box: Stop Server & Start Server + Status
$BtnStopServer = New-Object System.Windows.Forms.Button
$BtnStopServer.Text = "Stop Server"
$BtnStopServer.Font = $FontLabel
$BtnStopServer.Location = New-Object System.Drawing.Point(20, 160)
$BtnStopServer.Size = New-Object System.Drawing.Size(112, 30)
$BtnStopServer.BackColor = [System.Drawing.Color]::FromArgb(153, 45, 53)
$BtnStopServer.ForeColor = [System.Drawing.Color]::White
$BtnStopServer.FlatStyle = "Flat"
$BtnStopServer.FlatAppearance.BorderSize = 0
$Form.Controls.Add($BtnStopServer)

$BtnStartServer = New-Object System.Windows.Forms.Button
$BtnStartServer.Text = "Start Server"
$BtnStartServer.Font = $FontLabel
$BtnStartServer.Location = New-Object System.Drawing.Point(140, 160)
$BtnStartServer.Size = New-Object System.Drawing.Size(112, 30)
$BtnStartServer.BackColor = [System.Drawing.Color]::FromArgb(15, 139, 109)
$BtnStartServer.ForeColor = [System.Drawing.Color]::White
$BtnStartServer.FlatStyle = "Flat"
$BtnStartServer.FlatAppearance.BorderSize = 0
$BtnStartServer.Enabled = $false
$Form.Controls.Add($BtnStartServer)

$LblServerStatus = New-Object System.Windows.Forms.Label
$LblServerStatus.Text = "Starting..."
$LblServerStatus.Font = $FontSmall
$LblServerStatus.ForeColor = [System.Drawing.Color]::FromArgb(251, 191, 36)
$LblServerStatus.TextAlign = [System.Drawing.ContentAlignment]::MiddleRight
$LblServerStatus.Location = New-Object System.Drawing.Point(260, 165)
$LblServerStatus.Size = New-Object System.Drawing.Size(218, 20)
$Form.Controls.Add($LblServerStatus)

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
$script:ShortenPs = $null
$script:ShortenAsync = $null
$script:ShortenTargetVersion = 0
$script:ShortenFallbackUrl = ""

function Stop-CloudflaredProcess {
    if ($script:TunnelProcess -and -not $script:TunnelProcess.HasExited) {
        try { $script:TunnelProcess.Kill() } catch {}
        try { $script:TunnelProcess.WaitForExit(1500) } catch {}
    }
    $script:TunnelProcess = $null
}

function Dispose-ShortenTask {
    if ($script:ShortenPs) {
        try { $script:ShortenPs.Stop() } catch {}
        try { $script:ShortenPs.Dispose() } catch {}
    }
    $script:ShortenPs = $null
    $script:ShortenAsync = $null
}

function Launch-CloudflaredProcess {
    try {
        Stop-CloudflaredProcess
        if (Test-Path $TunnelLogFile) {
            Remove-Item $TunnelLogFile -Force -ErrorAction SilentlyContinue
        }
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $CloudflaredExe
        $psi.Arguments = "tunnel --url http://127.0.0.1:$($Config.Port) --logfile `"$TunnelLogFile`""
        $psi.CreateNoWindow = $true
        $psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
        $psi.UseShellExecute = $false
        $script:TunnelProcess = [System.Diagnostics.Process]::Start($psi)
    } catch {}
}

$Timer = New-Object System.Windows.Forms.Timer
$Timer.Interval = 900

$ShortenPollTimer = New-Object System.Windows.Forms.Timer
$ShortenPollTimer.Interval = 200
$ShortenPollTimer.Add_Tick({
    if ($null -eq $script:ShortenAsync -or $null -eq $script:ShortenPs) {
        $ShortenPollTimer.Stop()
        return
    }
    if ($script:ShortenAsync.IsCompleted) {
        $ShortenPollTimer.Stop()
        $resolvedUrl = $script:ShortenFallbackUrl
        try {
            $out = $script:ShortenPs.EndInvoke($script:ShortenAsync)
            if ($out -and $out.Count -gt 0 -and $out[0]) {
                $resolvedUrl = [string]$out[0]
            }
        } catch {}
        $targetVer = $script:ShortenTargetVersion
        Dispose-ShortenTask
        if ($targetVer -eq $script:SessionVersion -and $script:Server.IsRunning) {
            $TxtUrl.Text = $resolvedUrl
            $TxtUrl.ForeColor = [System.Drawing.Color]::FromArgb(16, 185, 129)
            $BtnReloadUrl.Enabled = $true
            $BtnCopyUrl.Enabled = $true
            if ($resolvedUrl -ne $script:ShortenFallbackUrl) {
                $LblServerStatus.Text = "Running (Short URL ready)"
            } else {
                $LblServerStatus.Text = "Running (Port $($Config.Port))"
            }
            $LblServerStatus.ForeColor = [System.Drawing.Color]::FromArgb(16, 185, 129)
        }
    }
})

$Timer.Add_Tick({
    if (-not $script:Server.IsRunning) {
        $Timer.Stop()
        return
    }
    if (Test-Path $TunnelLogFile) {
        try {
            $logContent = Get-Content $TunnelLogFile -Raw -ErrorAction SilentlyContinue
            if ($logContent -match "(https://[a-zA-Z0-9-]+\.trycloudflare\.com)") {
                $pubUrl = $Matches[1]
                $Timer.Stop()
                $TxtUrl.Text = "Shortening URL..."
                $LblServerStatus.Text = "Shortening URL..."
                $LblServerStatus.ForeColor = [System.Drawing.Color]::FromArgb(251, 191, 36)

                Dispose-ShortenTask
                $script:ShortenTargetVersion = $script:SessionVersion
                $script:ShortenFallbackUrl = $pubUrl
                $script:ShortenPs = [PowerShell]::Create().AddScript($script:ShortenWorkerScript).AddArgument($pubUrl)
                $script:ShortenAsync = $script:ShortenPs.BeginInvoke()
                $ShortenPollTimer.Start()
            }
        } catch {}
    }
})

function Stop-AllServerComponents {
    $script:SessionVersion++
    $Timer.Stop()
    $ShortenPollTimer.Stop()
    Dispose-ShortenTask
    if ($script:WebClient) {
        try { $script:WebClient.CancelAsync() } catch {}
    }
    $script:Server.Stop()
    Stop-CloudflaredProcess

    $TxtUrl.Text = "Server stopped"
    $TxtUrl.ForeColor = [System.Drawing.Color]::FromArgb(155, 165, 162)
    $BtnCopyUrl.Text = "Copy URL"
    $BtnCopyUrl.Enabled = $false
    $BtnReloadUrl.Enabled = $false
    $BtnStopServer.Enabled = $false
    $BtnStartServer.Enabled = $true
    $LblServerStatus.Text = "Stopped"
    $LblServerStatus.ForeColor = [System.Drawing.Color]::FromArgb(248, 113, 113)
}

function Start-AllServerComponents {
    $script:SessionVersion++
    $myVer = $script:SessionVersion
    $Timer.Stop()
    $ShortenPollTimer.Stop()
    Dispose-ShortenTask

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

    $BtnCopyUrl.Text = "Copy URL"
    $BtnCopyUrl.Enabled = $false
    $BtnReloadUrl.Enabled = $false
    $BtnStopServer.Enabled = $true
    $BtnStartServer.Enabled = $false
    $TxtUrl.ForeColor = [System.Drawing.Color]::FromArgb(16, 185, 129)

    if (Test-Path $CloudflaredExe) {
        try { Unblock-File -LiteralPath $CloudflaredExe -ErrorAction SilentlyContinue } catch {}
        $TxtUrl.Text = "Starting..."
        $LblServerStatus.Text = "Starting tunnel..."
        $LblServerStatus.ForeColor = [System.Drawing.Color]::FromArgb(251, 191, 36)
        Launch-CloudflaredProcess
        $Timer.Start()
    } else {
        $TxtUrl.Text = "Preparing URL..."
        $LblServerStatus.Text = "Downloading tunnel..."
        $LblServerStatus.ForeColor = [System.Drawing.Color]::FromArgb(251, 191, 36)
        $dlUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
        $script:WebClient = New-Object System.Net.WebClient
        $script:WebClient.Headers.Add("User-Agent", "Mozilla/5.0 KagojpotroServer/1.0")
        $script:WebClient.Add_DownloadFileCompleted({
            param($sender, $e)
            if ($myVer -ne $script:SessionVersion -or -not $script:Server.IsRunning) { return }
            if ($null -eq $e.Error -and -not $e.Cancelled -and (Test-Path $CloudflaredExe)) {
                try { Unblock-File -LiteralPath $CloudflaredExe -ErrorAction SilentlyContinue } catch {}
                $TxtUrl.Text = "Starting..."
                $LblServerStatus.Text = "Starting tunnel..."
                Launch-CloudflaredProcess
                $Timer.Start()
            } else {
                $TxtUrl.Text = "http://127.0.0.1:$($Config.Port)"
                $LblServerStatus.Text = "Local mode (Port $($Config.Port))"
                $LblServerStatus.ForeColor = [System.Drawing.Color]::FromArgb(16, 185, 129)
                $BtnCopyUrl.Enabled = $true
            }
        })
        $script:WebClient.DownloadFileAsync((New-Object System.Uri($dlUrl)), $CloudflaredExe)
    }
}

$BtnReloadUrl.Add_Click({
    if ($script:Server.IsRunning -and (Test-Path $CloudflaredExe)) {
        $script:SessionVersion++
        $BtnReloadUrl.Enabled = $false
        $BtnCopyUrl.Enabled = $false
        $BtnCopyUrl.Text = "Copy URL"
        $TxtUrl.Text = "Regenerating..."
        $LblServerStatus.Text = "Regenerating URL..."
        $LblServerStatus.ForeColor = [System.Drawing.Color]::FromArgb(251, 191, 36)
        $Timer.Stop()
        $ShortenPollTimer.Stop()
        Dispose-ShortenTask
        Launch-CloudflaredProcess
        $Timer.Start()
    }
})

$BtnStopServer.Add_Click({
    Stop-AllServerComponents
})

$BtnStartServer.Add_Click({
    Start-AllServerComponents
})

$Form.Add_Shown({
    $Form.Activate()
    Start-AllServerComponents
})

# Closing the window stops the server and exits completely
$Form.Add_FormClosing({
    $script:SessionVersion++
    $Timer.Stop()
    $ShortenPollTimer.Stop()
    Dispose-ShortenTask
    $TrayIcon.Visible = $false
    $TrayIcon.Dispose()
    $script:Server.Stop()
    Stop-CloudflaredProcess
})

[System.Windows.Forms.Application]::Run($Form)
