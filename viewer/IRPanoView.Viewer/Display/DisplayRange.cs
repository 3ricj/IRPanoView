namespace IRPanoView.Viewer.Display;

public static class DisplayRange
{
    public static int CelsiusToDisplay(double celsius, double displayMinC, double displayMaxC)
    {
        if (displayMaxC <= displayMinC)
        {
            return 0;
        }

        var scaled = ((celsius - displayMinC) / (displayMaxC - displayMinC)) * 255.0;
        return Math.Clamp((int)Math.Round(scaled), 0, 255);
    }
}
