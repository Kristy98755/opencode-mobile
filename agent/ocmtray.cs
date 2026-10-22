using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

// Tray icon for the OpenCode mobile agent: starts the agent with no console
// window at all, shows its state, and lets you start/stop/restart it.
internal static class OcmTray
{
    static readonly string Dir = @"C:\Users\XAKEP\AndroidStudioProjects\OpenCode Mobile\agent";
    static readonly string Log = @"C:\Users\XAKEP\AppData\Local\Temp\opencode\agent.log";
    static readonly string LockFile = Path.Combine(Path.GetTempPath(), "ocm-agent.lock");

    [DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr handle);

    private static NotifyIcon ni;
    private static Icon iconOn;
    private static Icon iconOff;
    private static Process child;
    private static ToolStripMenuItem miState;
    private static ToolStripMenuItem miStart;
    private static ToolStripMenuItem miStop;
    private static bool autoRespawn = true;
    private static DateTime lastSpawn = DateTime.MinValue;

    [STAThread]
    private static void Main()
    {
        bool created;
        using (var mutex = new Mutex(true, "Local\\OcmTraySingleInstance", out created))
        {
            if (!created) return;

            iconOn = MakeAppIcon(true);
            iconOff = MakeAppIcon(false);

            var menu = new ContextMenuStrip();
            miState = new ToolStripMenuItem("OpenCode agent") { Enabled = false };
            miStart = new ToolStripMenuItem("Start agent", null, (s, e) => { autoRespawn = true; EnsureAgent(); });
            miStop = new ToolStripMenuItem("Stop agent", null, (s, e) => { autoRespawn = false; StopAgent(); });
            var miRestart = new ToolStripMenuItem("Restart agent", null, (s, e) => { autoRespawn = true; StopAgent(); EnsureAgent(); });
            var miLog = new ToolStripMenuItem("Open log", null, (s, e) => OpenLog());
            var miQuit = new ToolStripMenuItem("Quit", null, (s, e) => { ni.Visible = false; Application.Exit(); });
            menu.Items.Add(miState);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(miStart);
            menu.Items.Add(miStop);
            menu.Items.Add(miRestart);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(miLog);
            menu.Items.Add(miQuit);

            ni = new NotifyIcon
            {
                Icon = iconOff,
                ContextMenuStrip = menu,
                Text = "OpenCode agent: ...",
                Visible = true,
            };
            ni.DoubleClick += (s, e) => OpenLog();

            EnsureAgent();
            Refresh();

            var timer = new System.Windows.Forms.Timer { Interval = 5000 };
            timer.Tick += (s, e) => Refresh();
            timer.Start();

            Application.Run();

            // tray exited on its own: leave the agent running, only clean up icon
            ni.Visible = false;
            ni.Dispose();
        }
    }

    private static void Refresh()
    {
        bool alive = AgentAlive();
        if (!alive && autoRespawn) EnsureAgent();
        alive = AgentAlive();
        if (ni.Icon != (alive ? iconOn : iconOff)) ni.Icon = alive ? iconOn : iconOff;
        string label = "OpenCode agent: " + (alive ? "running" : "stopped");
        if (ni.Text != label) ni.Text = label;
        miState.Text = label;
        miStart.Enabled = !alive;
        miStop.Enabled = alive;
    }

    private static int LockPid()
    {
        try { return int.Parse(File.ReadAllText(LockFile).Trim()); }
        catch { return -1; }
    }

    private static bool PidAlive(int pid)
    {
        if (pid <= 0) return false;
        try { Process.GetProcessById(pid); return true; }
        catch { return false; }
    }

    private static bool AgentAlive()
    {
        return PidAlive(LockPid()) || (child != null && !child.HasExited);
    }

    private static void EnsureAgent()
    {
        if (PidAlive(LockPid())) return;
        if (child != null && !child.HasExited) return;
        // crash-loop backoff: a child that died within 5s of spawn rests until
        // 30s have passed since the attempt
        if (child != null && child.HasExited && lastSpawn != DateTime.MinValue)
        {
            double lived = (child.ExitTime - child.StartTime).TotalSeconds;
            double since = (DateTime.Now - lastSpawn).TotalSeconds;
            if (lived < 5 && since < 30) return;
        }
        lastSpawn = DateTime.Now;
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "cmd.exe",
                Arguments = "/c \"" + Path.Combine(Dir, "run_agent.cmd") + "\"",
                WorkingDirectory = Dir,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            child = Process.Start(psi);
            AppendLog("[tray] agent spawned");
        }
        catch (Exception ex)
        {
            AppendLog("[tray] spawn failed: " + ex.Message);
        }
    }

    private static void StopAgent()
    {
        int pid = LockPid();
        if (PidAlive(pid))
        {
            try { Process.GetProcessById(pid).Kill(); }
            catch (Exception ex) { AppendLog("[tray] kill lock pid failed: " + ex.Message); }
        }
        if (child != null && !child.HasExited)
        {
            try { child.Kill(); }
            catch { /* already gone */ }
        }
        for (int i = 0; i < 30 && PidAlive(pid); i++) Thread.Sleep(100);
        AppendLog("[tray] agent stopped");
    }

    private static void OpenLog()
    {
        try
        {
            Process.Start(new ProcessStartInfo { FileName = Log, UseShellExecute = true });
        }
        catch (Exception ex)
        {
            AppendLog("[tray] open log failed: " + ex.Message);
        }
    }

    private static void AppendLog(string line)
    {
        try { File.AppendAllText(Log, DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fff") + "Z " + line + Environment.NewLine); }
        catch { /* best effort */ }
    }

    // Same logo as the app icon (drawable/ic_launcher_foreground.xml on the
    // #0D1117 background of @color/ic_launcher_background): the "> _" prompt
    // glyph in #E8842A. Path coords are the vector's 108-viewport points; the
    // group transform translate(27,27) scale(0.5) is applied in P(). A small
    // status dot (green = running, red = stopped) rides in the corner.
    private static Icon MakeAppIcon(bool running)
    {
        const int S = 32;
        using (var b = new Bitmap(S, S))
        {
            using (var g = Graphics.FromImage(b))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                using (var bg = new SolidBrush(Color.FromArgb(0x0D, 0x11, 0x17)))
                {
                    g.FillRectangle(bg, 0, 0, S, S);
                }
                var chevron = new[]
                {
                    P(18, 26), P(38, 54), P(18, 82),
                    P(28, 82), P(48, 54), P(28, 26),
                };
                var underscore = new[]
                {
                    P(54, 72), P(74, 72), P(74, 82), P(54, 82),
                };
                using (var glyph = new SolidBrush(Color.FromArgb(0xE8, 0x84, 0x2A)))
                {
                    g.FillPolygon(glyph, chevron);
                    g.FillPolygon(glyph, underscore);
                }
                const int d = 11;
                using (var dot = new SolidBrush(running
                             ? Color.FromArgb(76, 175, 80)
                             : Color.FromArgb(239, 83, 80)))
                using (var ring = new Pen(Color.FromArgb(0x0D, 0x11, 0x17), 2.2f))
                {
                    g.FillEllipse(dot, S - d - 1f, 1f, d, d);
                    g.DrawEllipse(ring, S - d - 1f, 1f, d, d);
                }
            }
            IntPtr h = b.GetHicon();
            try { return (Icon)Icon.FromHandle(h).Clone(); }
            finally { DestroyIcon(h); }
        }
    }

    // vector point (108 viewport) -> icon pixel, glyph fitted at 62% of the icon
    private static PointF P(float x, float y)
    {
        const int S = 32;
        float fx = 27f + 0.5f * x; // group: translate(27,27) scale(0.5)
        float fy = 27f + 0.5f * y;
        float scale = S * 0.62f / 28f; // glyph bbox is 28x28 in 108-space
        return new PointF(
            S / 2f + (fx - 50f) * scale, // bbox center is (50,54)
            S / 2f + (fy - 54f) * scale);
    }
}
