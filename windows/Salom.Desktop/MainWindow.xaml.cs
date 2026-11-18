using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using System.Net.WebSockets;
using System.Text.Json;
using Microsoft.Win32;
using Microsoft.VisualBasic;

namespace Salom.Desktop;

public partial class MainWindow : Window
{
    private readonly DesktopClient _client = new();
    private readonly LocalChatStore _store = new();
    private readonly ObservableCollection<ChatItem> _chats = new();
    private readonly ObservableCollection<ChatMessage> _messages = new();
    private ChatItem? _activeChat;
    private bool _syncInProgress;
    private bool _searchPlaceholder = true;
    private bool _peerOnline;
    private CancellationTokenSource? _realtimeCancellation;

    public MainWindow()
    {
        InitializeComponent();
        ChatsList.ItemsSource = _chats;
        MessagesList.ItemsSource = _messages;
        Loaded += Window_Loaded;
    }

    private async void Window_Loaded(object sender, RoutedEventArgs e)
    {
        if (!_client.HasSession) return;
        LoginStatus.Text = "Проверяю сохранённый вход…";
        try { await _client.ValidateSessionAsync(); await ShowWorkspaceAsync(); }
        catch (Exception error) { LoginStatus.Text = "Сессия истекла. Войди через Google ещё раз. " + error.Message; }
    }

    private async void GoogleSignIn_Click(object sender, RoutedEventArgs e)
    {
        var username = Interaction.InputBox("Username Салом (4–24 латинские буквы, цифры или _):", "Вход в Салом");
        if (string.IsNullOrWhiteSpace(username)) return;
        var displayName = Interaction.InputBox("Как показывать твоё имя в Салом?", "Профиль Салом", username.Trim());
        if (string.IsNullOrWhiteSpace(displayName)) return;
        GoogleButton.IsEnabled = false;
        LoginStatus.Text = "Открылся безопасный вход Google…";
        try { await _client.SignInAsync(displayName.Trim(), username.Trim().TrimStart('@')); await ShowWorkspaceAsync(); }
        catch (Exception error) { LoginStatus.Text = error.Message; }
        finally { GoogleButton.IsEnabled = true; }
    }

    private async Task ShowWorkspaceAsync()
    {
        LoginPane.Visibility = Visibility.Collapsed; Workspace.Visibility = Visibility.Visible;
        LoginStatus.Text = ""; ConnectionState.Text = "●  подключаюсь…";
        ConnectionState.Foreground = System.Windows.Media.Brushes.Orange;
        await LoadConversationsAsync();
    }

    private async Task LoadConversationsAsync()
    {
        try
        {
            var selectedId = _activeChat?.Id;
            var conversations = await _client.ConversationsAsync();
            _chats.Clear(); foreach (var chat in conversations) _chats.Add(chat);
            if (selectedId is not null) ChatsList.SelectedItem = _chats.FirstOrDefault(c => c.Id == selectedId);
            if (ChatsList.SelectedItem is null && _chats.Count > 0) ChatsList.SelectedIndex = 0;
        }
        catch (Exception error) { ShowToast("Не удалось загрузить список чатов: " + error.Message); }
    }

    private async void Search_Click(object sender, RoutedEventArgs e) => await SearchPeopleAsync();
    private async void Search_KeyDown(object sender, KeyEventArgs e) { if (e.Key == Key.Enter) { e.Handled = true; await SearchPeopleAsync(); } }

    private async Task SearchPeopleAsync()
    {
        var query = SearchBox.Text.Trim().TrimStart('@');
        if (_searchPlaceholder || query.Length < 1) { ShowToast("Введи имя или начало @username"); return; }
        SearchButton.IsEnabled = false;
        try
        {
            var found = await _client.SearchAsync(query);
            if (found.Count == 0) { ShowToast("Ничего не найдено"); return; }
            foreach (var person in found) if (_chats.All(c => c.Id != person.Id)) _chats.Insert(0, person);
            ChatsList.SelectedItem = _chats.FirstOrDefault(chat => found.Any(person => person.Id == chat.Id));
        }
        catch (Exception error) { ShowToast(error.Message); }
        finally { SearchButton.IsEnabled = true; }
    }

    private void Search_GotFocus(object sender, RoutedEventArgs e)
    {
        if (!_searchPlaceholder) return;
        SearchBox.Clear(); SearchBox.Foreground = System.Windows.Media.Brushes.White; _searchPlaceholder = false;
    }

    private void Search_LostFocus(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrWhiteSpace(SearchBox.Text)) return;
        SearchBox.Text = "Поиск по @username"; SearchBox.Foreground = System.Windows.Media.Brushes.Gray; _searchPlaceholder = true;
    }

    private async void ChatsList_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (ChatsList.SelectedItem is not ChatItem chat) return;
        _realtimeCancellation?.Cancel();
        _realtimeCancellation?.Dispose();
        _realtimeCancellation = new CancellationTokenSource();
        _peerOnline = false;
        _activeChat = chat; PeerName.Text = chat.Name; PeerUsername.Text = string.IsNullOrWhiteSpace(chat.Username) ? "" : "@" + chat.Username;
        _messages.Clear(); foreach (var message in _store.Load(chat.Id)) _messages.Add(message);
        await SyncSelectedChatAsync(true);
        _ = RealtimeLoopAsync(chat, _realtimeCancellation.Token);
    }

    private async Task RealtimeLoopAsync(ChatItem chat, CancellationToken cancellationToken)
    {
        var retryDelay = TimeSpan.FromSeconds(1);
        while (!cancellationToken.IsCancellationRequested && _activeChat?.Id == chat.Id)
        {
            try
            {
                using var socket = await _client.ConnectRealtimeAsync(chat.Id, cancellationToken);
                await Dispatcher.InvokeAsync(() => SetConnection("●  онлайн", true));
                await SyncSelectedChatAsync(false);
                using var pingCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                var pingTask = SendRealtimePingsAsync(socket, pingCancellation.Token);
                retryDelay = TimeSpan.FromSeconds(1);
                try
                {
                    while (!cancellationToken.IsCancellationRequested && socket.State == WebSocketState.Open)
                    {
                        using var eventDocument = await _client.ReceiveRealtimeEventAsync(socket, cancellationToken);
                        if (eventDocument is null) break;
                        await Dispatcher.InvokeAsync(() => HandleRealtimeEvent(chat, eventDocument.RootElement));
                    }
                }
                finally
                {
                    pingCancellation.Cancel();
                    try { await pingTask; } catch (OperationCanceledException) { }
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { break; }
            catch
            {
                await Dispatcher.InvokeAsync(() => SetConnection("●  переподключение…", false));
                await SyncSelectedChatAsync(false);
                try { await Task.Delay(retryDelay, cancellationToken); }
                catch (OperationCanceledException) { break; }
                retryDelay = TimeSpan.FromSeconds(Math.Min(30, retryDelay.TotalSeconds * 2));
            }
        }
    }

    private async Task SendRealtimePingsAsync(ClientWebSocket socket, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested && socket.State == WebSocketState.Open)
        {
            await Task.Delay(TimeSpan.FromSeconds(20), cancellationToken);
            if (socket.State != WebSocketState.Open) return;
            var ping = System.Text.Encoding.UTF8.GetBytes("{\"type\":\"ping\"}");
            await socket.SendAsync(ping, WebSocketMessageType.Text, true, cancellationToken);
            try { await _client.HeartbeatAsync(cancellationToken); } catch { }
        }
    }

    private void HandleRealtimeEvent(ChatItem chat, JsonElement payload)
    {
        if (_activeChat?.Id != chat.Id || !payload.TryGetProperty("type", out var typeValue)) return;
        var type = typeValue.GetString();
        if (type == "presence" && payload.TryGetProperty("online", out var online))
        {
            _peerOnline = online.GetBoolean();
            PeerUsername.Text = (string.IsNullOrWhiteSpace(chat.Username) ? "" : "@" + chat.Username + "  ·  ") + (_peerOnline ? "в сети" : "не в сети");
            return;
        }
        if (type == "typing" && payload.TryGetProperty("typing", out var typing))
        {
            PeerUsername.Text = typing.GetBoolean() ? "печатает…" : (string.IsNullOrWhiteSpace(chat.Username) ? "" : "@" + chat.Username + "  ·  ") + (_peerOnline ? "в сети" : "не в сети");
            return;
        }
        if (type == "message" && payload.TryGetProperty("message", out var incoming))
        {
            var message = _client.ParseRealtimeMessage(incoming);
            if (_messages.All(item => item.Id != message.Id))
            {
                _messages.Add(message); _store.Save(chat.Id, _messages); MessagesScroll.ScrollToEnd();
            }
            return;
        }
        if (type == "sync") _ = SyncSelectedChatAsync(false);
        else if (type == "call_invite") ShowToast("Входящий звонок. Звонки Windows-клиента пока не поддерживаются.");
    }

    private void SetConnection(string label, bool online)
    {
        ConnectionState.Text = label;
        ConnectionState.Foreground = online ? System.Windows.Media.Brushes.LightGreen : System.Windows.Media.Brushes.Orange;
    }

    private async Task SyncSelectedChatAsync(bool reportErrors)
    {
        if (_syncInProgress || _activeChat is null || !IsActive || Workspace.Visibility != Visibility.Visible) return;
        _syncInProgress = true;
        try
        {
            var chat = _activeChat;
            var after = _messages.Count == 0 ? 0 : _messages.Max(message => message.Id);
            var incoming = await _client.MessagesAsync(chat.Id, after);
            if (_activeChat?.Id != chat.Id) return;
            foreach (var message in incoming) _messages.Add(message);
            if (incoming.Count > 0) _store.Save(chat.Id, _messages);
            if (incoming.Count > 0) MessagesScroll.ScrollToEnd();
            ConnectionState.Text = "●  синхронизировано"; ConnectionState.Foreground = System.Windows.Media.Brushes.LightGreen;
        }
        catch (Exception error)
        {
            ConnectionState.Text = "●  офлайн"; ConnectionState.Foreground = System.Windows.Media.Brushes.Orange;
            if (reportErrors) ShowToast("Показываю сохранённую историю. " + error.Message);
        }
        finally { _syncInProgress = false; }
    }

    private async void Send_Click(object sender, RoutedEventArgs e) => await SendMessageAsync();
    private async void MessageBox_KeyDown(object sender, KeyEventArgs e) { if (e.Key == Key.Enter && Keyboard.Modifiers != ModifierKeys.Shift) { e.Handled = true; await SendMessageAsync(); } }

    private async Task SendMessageAsync()
    {
        if (_activeChat is null) { ShowToast("Сначала выбери чат"); return; }
        var text = MessageBox.Text.Trim(); if (text.Length == 0) return;
        SendButton.IsEnabled = false;
        try
        {
            var chat = _activeChat; var sent = await _client.SendAsync(chat, text);
            if (_activeChat?.Id == chat.Id) { _messages.Add(sent); _store.Save(chat.Id, _messages); MessagesScroll.ScrollToEnd(); MessageBox.Clear(); }
            var index = _chats.IndexOf(chat); if (index >= 0) _chats[index] = chat with { Preview = text };
        }
        catch (Exception error) { ShowToast(error.Message); }
        finally { SendButton.IsEnabled = true; }
    }

    private async void File_Click(object sender, RoutedEventArgs e)
    {
        if (_activeChat is null) { ShowToast("Сначала выбери чат"); return; }
        var dialog = new OpenFileDialog { Title = "Выбрать файл для отправки", CheckFileExists = true, Multiselect = false };
        if (dialog.ShowDialog(this) != true) return;
        FileButton.IsEnabled = false; FileButton.Content = "Загрузка…";
        try
        {
            var chat = _activeChat;
            var sent = await _client.SendAttachmentAsync(chat, dialog.FileName, MessageBox.Text.Trim());
            if (_activeChat?.Id == chat.Id)
            {
                if (_messages.All(item => item.Id != sent.Id)) _messages.Add(sent);
                _store.Save(chat.Id, _messages); MessagesScroll.ScrollToEnd(); MessageBox.Clear();
            }
            ShowToast("Файл отправлен");
        }
        catch (Exception error) { ShowToast("Не удалось отправить файл: " + error.Message); }
        finally { FileButton.IsEnabled = true; FileButton.Content = "Файл"; }
    }

    private async void DownloadAttachment_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not System.Windows.Controls.Button { Tag: ChatMessage message } || string.IsNullOrWhiteSpace(message.AttachmentId)) return;
        var dialog = new SaveFileDialog { FileName = Path.GetFileName(message.AttachmentName ?? "salom-file") };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            await _client.DownloadAttachmentAsync(message.AttachmentId, dialog.FileName);
            ShowToast("Файл сохранён");
        }
        catch (Exception error) { ShowToast("Не удалось скачать файл: " + error.Message); }
    }

    private void SignOut_Click(object sender, RoutedEventArgs e)
    {
        _realtimeCancellation?.Cancel();
        _client.SignOut(); _activeChat = null; _chats.Clear(); _messages.Clear();
        Workspace.Visibility = Visibility.Collapsed; LoginPane.Visibility = Visibility.Visible;
        ConnectionState.Text = "●  отключено"; LoginStatus.Text = "Ты вышел из аккаунта на этом компьютере.";
    }

    protected override void OnClosed(EventArgs e)
    {
        _realtimeCancellation?.Cancel();
        _realtimeCancellation?.Dispose();
        base.OnClosed(e);
    }

    private async void ShowToast(string message)
    {
        Toast.Text = message; Toast.Visibility = Visibility.Visible;
        await Task.Delay(3500); Toast.Visibility = Visibility.Collapsed;
    }
}
