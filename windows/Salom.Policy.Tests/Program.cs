using Salom.Desktop;

if (!ComposerInputPolicy.ShouldSend(enterPressed: true, shiftPressed: false)) throw new Exception("Enter must send");
if (ComposerInputPolicy.ShouldSend(enterPressed: true, shiftPressed: true)) throw new Exception("Shift+Enter must insert a newline");
if (ComposerInputPolicy.ShouldSend(enterPressed: false, shiftPressed: false)) throw new Exception("Other keys must not send");
Console.WriteLine("Composer keyboard policy: 3 checks passed");
