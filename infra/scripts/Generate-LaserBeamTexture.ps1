# Generates the animated laser bolt textures (8-frame vertical strips, 16x16 per frame).
# u (x) runs along the beam, v (y) across it.
#
# Every texel is written with alpha 253 — the pack shaders' emissive-plasma marker
# (entity.fsh / rendertype_item_entity_translucent_cull.fsh). The shader forces those
# texels opaque and full-bright, so the beam's look lives entirely in the RGB channel:
# grayscale traveling pulses that gate to 0 in the troughs and at the cross-section
# edges. Black texels emit nothing, so only the animated pulses carry light, and the
# formerly transparent sections render as solid black. The dye tint (config color for
# players, red for AI tracers) multiplies the grayscale, giving the intended hue.
#
# Alpha must stay 253 on EVERY texel of EVERY frame: frame interpolation and mipmaps
# average alpha, and any blend away from 253 drops the texel out of the plasma path.
Add-Type -AssemblyName System.Drawing

$texDir = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\skypvp\textures\item"
$frames = 8
$size = 16
$tau = [Math]::PI * 2
$markerAlpha = 253   # keep in sync with the alpha == 253 check in the .fsh files

function Get-SmoothStep {
    param([double]$Edge0, [double]$Edge1, [double]$X)
    $t = [Math]::Max(0.0, [Math]::Min(1.0, ($X - $Edge0) / ($Edge1 - $Edge0)))
    return $t * $t * (3.0 - 2.0 * $t)
}

function New-BeamStrip {
    param(
        [string]$Name,
        [double]$Sigma,      # cross-section gaussian width (pixels)
        [double]$EdgeBlack,  # cross-section value at/below this is fully black
        [double]$EdgeFull,   # cross-section value at/above this is fully lit
        [double]$GateLo,     # wave value at/below this is a black gap between pulses
        [double]$GateHi,     # wave value at/above this is a full pulse
        [double]$FrameTime
    )
    $bmp = New-Object System.Drawing.Bitmap $size, ($size * $frames)
    for ($f = 0; $f -lt $frames; $f++) {
        $phase = $f / [double]$frames
        for ($y = 0; $y -lt $size; $y++) {
            $dv = ($y - 7.5) / $Sigma
            $cross = [Math]::Exp(-0.5 * $dv * $dv)
            $edge = Get-SmoothStep $EdgeBlack $EdgeFull $cross
            for ($x = 0; $x -lt $size; $x++) {
                # Two traveling waves moving at different speeds = plasma pulses
                # running along the bolt as the frames advance.
                $w1 = 0.5 + 0.5 * [Math]::Sin($tau * (2.0 * $x / $size - $phase))
                $w2 = 0.5 + 0.5 * [Math]::Sin($tau * (3.0 * $x / $size + 2.0 * $phase) + $y * 0.7)
                $wave = 0.6 * $w1 + 0.4 * $w2
                $gate = Get-SmoothStep $GateLo $GateHi $wave
                $i = $edge * $gate * (0.35 + 0.65 * $wave)
                $g = [int][Math]::Round(255 * [Math]::Min(1.0, $i))
                $bmp.SetPixel($x, $y + $f * $size, [System.Drawing.Color]::FromArgb($markerAlpha, $g, $g, $g))
            }
        }
    }
    $png = Join-Path $texDir "$Name.png"
    $bmp.Save($png, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $meta = '{ "animation": { "frametime": ' + [int]$FrameTime + ', "interpolate": true } }'
    Set-Content -Path "$png.mcmeta" -Value $meta -Encoding ascii
    Write-Output "Wrote $png (+.mcmeta)"
}

# Tinted core: tight bright spine, hard black gaps between pulses.
New-BeamStrip -Name "laser_beam" -Sigma 4.5 -EdgeBlack 0.30 -EdgeFull 0.65 -GateLo 0.25 -GateHi 0.60 -FrameTime 2
# Shell (the visible beam surface): wider lit band, black rim where the halo used to fade out.
New-BeamStrip -Name "laser_halo" -Sigma 5.5 -EdgeBlack 0.18 -EdgeFull 0.55 -GateLo 0.30 -GateHi 0.65 -FrameTime 2
# Center line: narrow and almost always lit — the beam keeps a visible spine between pulses.
New-BeamStrip -Name "laser_hot" -Sigma 2.2 -EdgeBlack 0.50 -EdgeFull 0.80 -GateLo 0.10 -GateHi 0.40 -FrameTime 2
