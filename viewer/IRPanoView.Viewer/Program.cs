namespace IRPanoView.Viewer;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length > 0)
        {
            if (args.Contains("--help", StringComparer.OrdinalIgnoreCase)
                || args.Contains("-h", StringComparer.OrdinalIgnoreCase))
            {
                if (args.Any(a => a.Equals("--snapshot", StringComparison.OrdinalIgnoreCase)))
                {
                    return SnapshotCli.TryRun(args);
                }

                Console.WriteLine("GUI mode: run with no arguments.");
                Console.WriteLine("Snapshot mode: IRPanoView.Viewer --snapshot --help");
                return 0;
            }

            var snapshotExit = SnapshotCli.TryRun(args);
            if (snapshotExit >= 0)
            {
                return snapshotExit;
            }

            var tileJetExit = TileJetCli.TryRun(args);
            if (tileJetExit >= 0)
            {
                return tileJetExit;
            }

            var probeExit = RawProbeCli.TryRun(args);
            if (probeExit >= 0)
            {
                return probeExit;
            }
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
        return 0;
    }
}
