using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Windows;

namespace Salom.Setup;

public partial class SetupWindow : Window
{
    private readonly string _installPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "SALOM");
    public SetupWindow() { InitializeComponent(); LocationText.Text = _installPath; }
    private void Cancel_Click(object sender, RoutedEventArgs e) => Close();

    private async void Install_Click(object sender, RoutedEventArgs e)
    {
        InstallButton.IsEnabled = false; CancelButton.IsEnabled = false; InstallProgress.Visibility = Visibility.Visible;
        try
        {
            await Task.Run(InstallFiles);
            CreateShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Салом"), "Салом.lnk");
            CreateShortcut(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Салом.lnk");
            var exe = Path.Combine(_installPath, "Salom.Desktop.exe");
            Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true });
            MessageBox.Show(this, "Салом установлен.\n\nДля входа в Windows-версии нужны Desktop OAuth и Firebase Web API настройки.", "Готово", MessageBoxButton.OK, MessageBoxImage.Information);
            Close();
        }
        catch (Exception ex) { InstallProgress.Visibility = Visibility.Collapsed; InstallButton.IsEnabled = true; CancelButton.IsEnabled = true; MessageBox.Show(this, "Не удалось установить Салом:\n" + ex.Message, "Ошибка установки", MessageBoxButton.OK, MessageBoxImage.Error); }
    }

    private void InstallFiles()
    {
        using var archiveStream = Assembly.GetExecutingAssembly().GetManifestResourceStream("Salom.Setup.SalomDesktop.zip") ?? throw new InvalidOperationException("В установщик не включён пакет приложения.");
        using var archive = new ZipArchive(archiveStream, ZipArchiveMode.Read);
        var root = Path.GetFullPath(_installPath) + Path.DirectorySeparatorChar;
        Directory.CreateDirectory(_installPath);
        foreach (var entry in archive.Entries)
        {
            var destination = Path.GetFullPath(Path.Combine(_installPath, entry.FullName));
            if (!destination.StartsWith(root, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Недопустимый путь внутри установочного архива.");
            if (string.IsNullOrEmpty(entry.Name)) { Directory.CreateDirectory(destination); continue; }
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            entry.ExtractToFile(destination, overwrite: true);
        }
    }

    private void CreateShortcut(string folder, string name)
    {
        Directory.CreateDirectory(folder);
        var shellType = Type.GetTypeFromProgID("WScript.Shell") ?? throw new InvalidOperationException("Windows Script Host недоступен.");
        dynamic shell = Activator.CreateInstance(shellType)!;
        dynamic shortcut = shell.CreateShortcut(Path.Combine(folder, name));
        shortcut.TargetPath = Path.Combine(_installPath, "Salom.Desktop.exe");
        shortcut.WorkingDirectory = _installPath;
        shortcut.Description = "Салом для Windows";
        shortcut.Save();
        Marshal.FinalReleaseComObject(shortcut); Marshal.FinalReleaseComObject(shell);
    }
}
