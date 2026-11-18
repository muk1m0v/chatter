using System.IO;
using System.Text.Json;
using System.Windows;
using Microsoft.Web.WebView2.Core;

namespace Salom.Desktop;

public partial class CallWindow : Window
{
    private readonly DesktopClient _client;
    private readonly string _callId;
    private bool _isCaller;
    private bool _ended;
    private bool _webReady;
    private bool _callAccepted;
    private readonly List<JsonElement> _pendingEvents = new();
    private readonly TaskCompletionSource<bool> _mediaReady = new(TaskCreationOptions.RunContinuationsAsynchronously);
    internal static CallWindow? Active { get; private set; }

    internal CallWindow(DesktopClient client, string callId, string peerName, bool isCaller)
    {
        InitializeComponent();
        _client = client; _callId = callId; _isCaller = isCaller;
        Title = (isCaller ? "Звоним: " : "Звонок: ") + peerName;
        Active = this;
        Loaded += InitializeCallAsync;
        Closed += (_, _) =>
        {
            if (Active == this) Active = null;
            if (!_ended) _ = EndCallAsync();
            _ended = true;
        };
    }

    internal async Task ReceiveEventAsync(JsonElement payload)
    {
        if (_ended || !payload.TryGetProperty("call_id", out var id) || id.GetString() != _callId) return;
        var type = payload.TryGetProperty("type", out var t) ? t.GetString() : "";
        if (!_webReady)
        {
            _pendingEvents.Add(payload.Clone());
            return;
        }
        await ProcessEventAsync(payload, type);
    }

    private async Task ProcessEventAsync(JsonElement payload, string? type)
    {
        if (type == "call_signal" && payload.TryGetProperty("kind", out var kind) && payload.TryGetProperty("payload", out var data))
        {
            var json = JsonSerializer.Serialize(new { kind = kind.GetString(), payload = data });
            await Dispatcher.InvokeAsync(async () => await CallBrowser.ExecuteScriptAsync($"window.salomReceive({JsonSerializer.Serialize(json)})"));
        }
        else if (type == "call_state" && payload.TryGetProperty("state", out var accepted) && accepted.GetString() == "accepted" && _isCaller)
        {
            _callAccepted = true;
            await CallBrowser.ExecuteScriptAsync("window.salomCreateOffer()");
        }
        else if (type == "call_state" && payload.TryGetProperty("state", out var state) && state.GetString() is "declined" or "cancelled" or "ended" or "missed")
        {
            await Dispatcher.InvokeAsync(() => { CallStatus.Text = state.GetString() switch { "declined" => "Звонок отклонён", "missed" => "Нет ответа", _ => "Звонок завершён" }; });
            await Task.Delay(1600);
            _ended = true;
            await Dispatcher.InvokeAsync(Close);
        }
    }

    private async void InitializeCallAsync(object sender, RoutedEventArgs e)
    {
        try
        {
            var folder = Path.Combine(AppContext.BaseDirectory, "CallWeb");
            if (!Directory.Exists(folder)) throw new DirectoryNotFoundException("Не найдены файлы звонка.");
            await CallBrowser.EnsureCoreWebView2Async();
            CallBrowser.CoreWebView2.SetVirtualHostNameToFolderMapping("salom-call.local", folder, CoreWebView2HostResourceAccessKind.DenyCors);
            CallBrowser.CoreWebView2.PermissionRequested += (_, args) =>
            {
                if (args.PermissionKind is CoreWebView2PermissionKind.Microphone or CoreWebView2PermissionKind.Camera)
                    args.State = CoreWebView2PermissionState.Allow;
            };
            CallBrowser.CoreWebView2.WebMessageReceived += OnWebMessage;
            var navigation = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            void Completed(object? _, CoreWebView2NavigationCompletedEventArgs args) { CallBrowser.NavigationCompleted -= Completed; navigation.TrySetResult(args.IsSuccess); }
            CallBrowser.NavigationCompleted += Completed;
            CallBrowser.Source = new Uri("https://salom-call.local/call.html");
            if (!await navigation.Task.WaitAsync(TimeSpan.FromSeconds(15))) throw new InvalidOperationException("Не удалось открыть интерфейс звонка.");
            using var config = await _client.CallConfigAsync();
            var ice = config.RootElement.GetProperty("ice_servers").GetRawText();
            var script = $"window.salomStart({JsonSerializer.Serialize(ice)},{(_isCaller ? "true" : "false")}).then(()=>window.chrome.webview.postMessage({{type:'ready'}}))";
            await CallBrowser.ExecuteScriptAsync(script);
            await _mediaReady.Task.WaitAsync(TimeSpan.FromSeconds(25));
            _webReady = true;
            foreach (var pending in _pendingEvents.ToArray())
                await ProcessEventAsync(pending, pending.TryGetProperty("type", out var eventType) ? eventType.GetString() : null);
            _pendingEvents.Clear();
            CallStatus.Text = _isCaller ? "Звоним…" : "Соединяемся…";
            if (!_isCaller) { await _client.UpdateCallAsync(_callId, "accept"); _callAccepted = true; }
        }
        catch (Exception ex) { CallStatus.Text = "Не удалось запустить звонок: " + ex.Message; }
    }

    private async void OnWebMessage(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        try
        {
            using var message = JsonDocument.Parse(e.WebMessageAsJson);
            var root = message.RootElement;
            var type = root.GetProperty("type").GetString();
            if (type == "ready") _mediaReady.TrySetResult(true);
            else if (type == "signal")
                await _client.SignalCallAsync(_callId, root.GetProperty("kind").GetString()!, root.GetProperty("payload"));
            else if (type == "state")
                await Dispatcher.InvokeAsync(() => CallStatus.Text = root.GetProperty("value").GetString() switch
                {
                    "connected" => "Звонок идёт",
                    "connecting" => "Соединяемся…",
                    "failed" => "Не удалось соединиться. Проверьте сеть и TURN.",
                    _ => "Звонок · " + root.GetProperty("value").GetString()
                });
            else if (type == "hangup")
            {
                _ended = true;
                try { await _client.UpdateCallAsync(_callId, "end"); } catch { }
                await Dispatcher.InvokeAsync(Close);
            }
            else if (type == "error")
            {
                var error = root.GetProperty("value").GetString() ?? "WebRTC error";
                _mediaReady.TrySetException(new InvalidOperationException(error));
                await Dispatcher.InvokeAsync(() => CallStatus.Text = "Ошибка звонка: " + error);
            }
        }
        catch (Exception ex) { await Dispatcher.InvokeAsync(() => CallStatus.Text = "Ошибка сигнализации: " + ex.Message); }
    }

    private async Task EndCallAsync()
    {
        try { await _client.UpdateCallAsync(_callId, _callAccepted ? "end" : _isCaller ? "cancel" : "decline"); }
        catch { }
    }

    internal static async Task<CallWindow?> StartOutgoingAsync(DesktopClient client, string peerId, string peerName, Window owner)
    {
        using var response = await client.CreateCallAsync(peerId);
        var id = response.RootElement.GetProperty("call_id").GetString()!;
        var window = new CallWindow(client, id, peerName, true) { Owner = owner };
        window.Show();
        return window;
    }

    internal static async Task<CallWindow?> AnswerIncomingAsync(DesktopClient client, string callId, string peerName, Window owner)
    {
        var window = new CallWindow(client, callId, peerName, false) { Owner = owner };
        window.Show();
        return window;
    }
}
