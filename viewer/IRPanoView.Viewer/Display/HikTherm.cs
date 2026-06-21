namespace IRPanoView.Viewer.Display;

public static class HikTherm
{
    public const int TempBiasU16 = 0x37C0;
    public const int TempScale = 64;
    public const double KelvinOffsetC = 273.15;

    public static int StoredU16FromRaw(int rawU16) => (rawU16 + TempBiasU16) & 0xFFFF;

    public static double CelsiusFromStoredU16(int storedU16) =>
        storedU16 / (double)TempScale - KelvinOffsetC;

    public static double CelsiusFromRawU16(int rawU16) =>
        CelsiusFromStoredU16(StoredU16FromRaw(rawU16));
}
