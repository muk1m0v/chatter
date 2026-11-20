namespace Salom.Desktop;

internal static class ComposerInputPolicy
{
    internal static bool ShouldSend(bool enterPressed, bool shiftPressed) => enterPressed && !shiftPressed;
}
