# Application Icon

YuMark needs launcher icons in the following directories:

```
app/src/main/res/
├── mipmap-mdpi/ic_launcher.png        (48x48)
├── mipmap-hdpi/ic_launcher.png        (72x72)
├── mipmap-xhdpi/ic_launcher.png       (96x96)
├── mipmap-xxhdpi/ic_launcher.png      (144x144)
├── mipmap-xxxhdpi/ic_launcher.png     (192x192)
└── mipmap-anydpi-v26/
    ├── ic_launcher.xml                (Adaptive icon)
    └── ic_launcher_round.xml          (Round adaptive icon)
```

## Design Concept

- **Primary Color**: Green (#006C4C) - representing Markdown/writing
- **Icon Shape**: Stylized "Y" or document with pen
- **Style**: Modern, minimalist, flat design

## Generate Icons

Use Android Studio's Image Asset Studio:
1. Right-click `res` folder → New → Image Asset
2. Choose Icon Type: Launcher Icons (Adaptive and Legacy)
3. Upload your source image (512x512 PNG recommended)
4. Configure foreground, background layers
5. Generate all sizes automatically

## Quick Icon Template

For testing, you can use a solid color placeholder:
- Background: #006C4C (green)
- Foreground: White "Y" letter (Roboto Bold)
