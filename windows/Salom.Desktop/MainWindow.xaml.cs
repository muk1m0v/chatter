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
    private readonly System.Windows.Forms.NotifyIcon _trayIcon;
    private ChatItem? _activeChat;
    private bool _syncInProgress;
    private bool _searchPlaceholder = true;
    private bool _peerOnline;
    private string? _pendingCallInvite;
    private CancellationTokenSource? _realtimeCancellation;

    public MainWindow()
    {
        InitializeComponent();
        _trayIcon = new System.Windows.Forms.NotifyIcon
        {
            Icon = LoadTrayIcon(),
            Text = "Салом",
            Visible = true,
            ContextMenuStrip = new System.Windows.Forms.ContextMenuStrip()
        };
        _trayIcon.ContextMenuStrip.Items.Add("Открыть Салом", null, (_, _) => Dispatcher.Invoke(() => { WindowState = WindowState.Normal; Activate(); }));
        _trayIcon.ContextMenuStrip.Items.Add("Выход", null, (_, _) => Dispatcher.Invoke(Close));
        ChatsList.ItemsSource = _chats;
        MessagesList.ItemsSource = _messages;
        Loaded += Window_Loaded;
    }

    private static System.Drawing.Icon LoadTrayIcon()
    {
        var resource = System.Windows.Application.GetResourceStream(new Uri("pack://application:,,,/assets/salom.ico", UriKind.Absolute))
            ?? throw new InvalidOperationException("Иконка Салом не включена в приложение.");
        using var stream = resource.Stream;
        return new System.Drawing.Icon(stream);
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

    private async void EmailSignIn_Click(object sender, RoutedEventArgs e) {
        var email = Interaction.InputBox("Email для входа:", "Салом · Email").Trim();
        if (email.Length == 0) return;
        try {
            using var sent = await _client.EmailAsync("code", new { email });
            var code = Interaction.InputBox("6 цифр из письма. Код действует 5 минут.", "Подтверждение Email").Trim();
            if (code.Length == 0) return;
            using var verified = await _client.EmailAsync("verify", new { email, code });
            if (verified.RootElement.TryGetProperty("needs_username", out var needs) && needs.GetBoolean()) {
                var username = Interaction.InputBox("Выбери username:", "Новый аккаунт");
                var name = Interaction.InputBox("Твоё имя:", "Новый аккаунт", username);
                using var registered = await _client.EmailAsync("register", new { email, ticket = verified.RootElement.GetProperty("ticket").GetString(), username, name });
                _client.AcceptEmail(registered);
            } else _client.AcceptEmail(verified);
            await ShowWorkspaceAsync();
        } catch (Exception error) { LoginStatus.Text = error.Message; }
    }
    private async Task ShowWorkspaceAsync()
    {
        LoginPane.Visibility = Visibility.Collapsed; Workspace.Visibility = Visibility.Visible;
        LoginStatus.Text = ""; ConnectionState.Text = "●  подключаюсь…";
        ConnectionState.Foreground = System.Windows.Media.Brushes.Orange;
        await LoadConversationsAsync();
        _realtimeCancellation?.Cancel();
        _realtimeCancellation?.Dispose();
        _realtimeCancellation = new CancellationTokenSource();
        _ = RealtimeLoopAsync(_realtimeCancellation.Token);
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
    private async void Search_KeyDown(object sender, System.Windows.Input.KeyEventArgs e) { if (e.Key == Key.Enter) { e.Handled = true; await SearchPeopleAsync(); } }

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
        _peerOnline = false;
        _activeChat = chat; PeerName.Text = chat.Name; PeerUsername.Text = string.IsNullOrWhiteSpace(chat.Username) ? "" : "@" + chat.Username;
        _messages.Clear(); foreach (var message in _store.Load(chat.Id)) _messages.Add(message);
        await SyncSelectedChatAsync(true);
    }

    private async Task RealtimeLoopAsync(CancellationToken cancellationToken)
    {
        var retryDelay = TimeSpan.FromSeconds(1);
        while (!cancellationToken.IsCancellationRequested && _client.HasSession)
        {
            try
            {
                using var socket = await _client.ConnectRealtimeAsync("*", cancellationToken);
                await Dispatcher.InvokeAsync(() => SetConnection("●  онлайн", true));
                await LoadConversationsAsync();
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
                        await Dispatcher.InvokeAsync(() => HandleRealtimeEvent(eventDocument.RootElement));
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

    private void HandleRealtimeEvent(JsonElement payload)
    {
        if (!payload.TryGetProperty("type", out var typeValue)) return;
        var type = typeValue.GetString();
        var eventPeerId = payload.TryGetProperty("peer_id", out var peerValue) ? peerValue.GetString() : null;
        var activeChat = _activeChat;
        if (type == "presence" && activeChat is not null && activeChat.Id == eventPeerId && payload.TryGetProperty("online", out var online))
        {
            _peerOnline = online.GetBoolean();
            PeerUsername.Text = (string.IsNullOrWhiteSpace(activeChat.Username) ? "" : "@" + activeChat.Username + "  ·  ") + (_peerOnline ? "в сети" : "не в сети");
            return;
        }
        if (type == "typing" && activeChat is not null && activeChat.Id == eventPeerId && payload.TryGetProperty("typing", out var typing))
        {
            PeerUsername.Text = typing.GetBoolean() ? "печатает…" : (string.IsNullOrWhiteSpace(activeChat.Username) ? "" : "@" + activeChat.Username + "  ·  ") + (_peerOnline ? "в сети" : "не в сети");
            return;
        }
        if (type == "message" && payload.TryGetProperty("message", out var incoming))
        {
            var message = _client.ParseRealtimeMessage(incoming);
            var peerId = message.IsMine ? incoming.GetProperty("recipient_id").GetString() : incoming.GetProperty("sender_id").GetString();
            var peer = _chats.FirstOrDefault(item => item.Id == peerId);
            var preview = string.IsNullOrWhiteSpace(message.Text) ? message.AttachmentName ?? "Вложение" : message.Text;
            if (peer is null)
            {
                NotifyIncoming("Новое сообщение", preview);
                _ = LoadConversationsAsync();
                return;
            }
            var chatIndex = _chats.IndexOf(peer);
            if (chatIndex >= 0) _chats[chatIndex] = peer with { Preview = preview };
            if (_activeChat?.Id == peerId)
            {
                if (_messages.All(item => item.Id != message.Id)) _messages.Add(message);
                _store.Save(peer.Id, _messages); MessagesScroll.ScrollToEnd();
            }
            else NotifyIncoming(peer.Name, preview);
            _ = LoadConversationsAsync();
            return;
        }
        if (type == "sync") { _ = LoadConversationsAsync(); if (activeChat?.Id == eventPeerId) _ = SyncSelectedChatAsync(false); }
        else if (type == "call_invite") _ = HandleIncomingCallAsync(payload.Clone());
        else if (type is "call_signal" or "call_state") _ = CallWindow.Active?.ReceiveEventAsync(payload.Clone());
    }

    private async Task HandleIncomingCallAsync(JsonElement payload)
    {
        var callId = payload.TryGetProperty("call_id", out var id) ? id.GetString() : null;
        var callerId = payload.TryGetProperty("peer_id", out var peerId) ? peerId.GetString() : null;
        var callerName = payload.TryGetProperty("caller_name", out var name) ? name.GetString() ?? "Салом" : "Салом";
        if (string.IsNullOrWhiteSpace(callId) || _pendingCallInvite == callId) return;
        _pendingCallInvite = callId;
        try
        {
            var decision = await Dispatcher.InvokeAsync(() => System.Windows.MessageBox.Show(this, $"{callerName} звонит.\n\nОтветить с камерой и микрофоном?", "Входящий звонок · Салом", MessageBoxButton.YesNo, MessageBoxImage.Question));
            if (decision == MessageBoxResult.Yes)
            {
                if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
                Activate();
                await CallWindow.AnswerIncomingAsync(_client, callId, callerName, this);
            }
            else if (!string.IsNullOrWhiteSpace(callerId)) await _client.UpdateCallAsync(callId, "decline");
        }
        catch (Exception error) { ShowToast("Не удалось открыть звонок: " + error.Message); }
        finally { if (_pendingCallInvite == callId) _pendingCallInvite = null; }
    }

    private async void Call_Click(object sender, RoutedEventArgs e)
    {
        if (_activeChat is null) { ShowToast("Сначала выбери чат"); return; }
        CallButton.IsEnabled = false;
        try { await CallWindow.StartOutgoingAsync(_client, _activeChat.Id, _activeChat.Name, this); }
        catch (Exception error) { ShowToast("Не удалось начать звонок: " + error.Message); }
        finally { CallButton.IsEnabled = true; }
    }

    private void NotifyIncoming(string title, string body)
    {
        if (_activeChat is not null && title == _activeChat.Name) return;
        if (IsActive && WindowState != WindowState.Minimized)
        {
            ShowToast(title + ": " + (body.Length > 110 ? body[..110] + "…" : body));
            return;
        }
        var preview = body.Length > 110 ? body[..110] + "…" : body;
        _trayIcon.ShowBalloonTip(4500, "Салом · " + title, preview, System.Windows.Forms.ToolTipIcon.Info);
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
            if (chat.UnreadCount > 0 || incoming.Count > 0) await LoadConversationsAsync();
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
    private async void MessageBox_KeyDown(object sender, System.Windows.Input.KeyEventArgs e) { if (ComposerInputPolicy.ShouldSend(e.Key == Key.Enter, Keyboard.Modifiers.HasFlag(ModifierKeys.Shift))) { e.Handled = true; await SendMessageAsync(); } }

    private async Task SendMessageAsync()
    {
        if (!SendButton.IsEnabled) return;
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
        var dialog = new Microsoft.Win32.OpenFileDialog { Title = "Выбрать файл для отправки", CheckFileExists = true, Multiselect = false };
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
        var dialog = new Microsoft.Win32.SaveFileDialog { FileName = Path.GetFileName(message.AttachmentName ?? "salom-file") };
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
        _realtimeCancellation?.Dispose(); _realtimeCancellation = null;
        Workspace.Visibility = Visibility.Collapsed; LoginPane.Visibility = Visibility.Visible;
        ConnectionState.Text = "●  отключено"; LoginStatus.Text = "Ты вышел из аккаунта на этом компьютере.";
    }

    protected override void OnClosed(EventArgs e)
    {
        _realtimeCancellation?.Cancel();
        _realtimeCancellation?.Dispose();
        _trayIcon.Visible = false;
        _trayIcon.Dispose();
        base.OnClosed(e);
    }

    private async void ShowToast(string message)
    {
        Toast.Text = message; Toast.Visibility = Visibility.Visible;
        await Task.Delay(3500); Toast.Visibility = Visibility.Collapsed;
    }
}
