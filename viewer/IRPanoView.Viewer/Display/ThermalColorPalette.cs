namespace IRPanoView.Viewer.Display;

public enum ThermalColorPalette
{
    WhiteHot,
    BlackHot,
    Ironbow,
    Rainbow,
    RedHot,
    Lava,
    Jet,
    Plasma,
    Turbo,
    Cividis,
    Twilight,
    GreysInverted,
    Inferno,
    Magma,
    Hot,
    Viridis,
    Grayscale,
}

public static class ThermalPaletteLabels
{
    public static string Label(ThermalColorPalette palette) => palette switch
    {
        ThermalColorPalette.WhiteHot => "White hot",
        ThermalColorPalette.BlackHot => "Black hot",
        ThermalColorPalette.Ironbow => "Ironbow",
        ThermalColorPalette.Rainbow => "Rainbow",
        ThermalColorPalette.RedHot => "Red hot",
        ThermalColorPalette.Lava => "Lava",
        ThermalColorPalette.Jet => "Jet",
        ThermalColorPalette.Plasma => "Plasma",
        ThermalColorPalette.Turbo => "Turbo",
        ThermalColorPalette.Cividis => "Cividis",
        ThermalColorPalette.Twilight => "Twilight",
        ThermalColorPalette.GreysInverted => "Greys (inverted)",
        ThermalColorPalette.Inferno => "Inferno",
        ThermalColorPalette.Magma => "Magma",
        ThermalColorPalette.Hot => "Hot",
        ThermalColorPalette.Viridis => "Viridis",
        ThermalColorPalette.Grayscale => "Grayscale",
        _ => palette.ToString(),
    };

    public static ThermalColorPalette Default => ThermalColorPalette.Jet;
}
