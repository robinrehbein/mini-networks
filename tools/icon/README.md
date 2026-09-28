# App-Icon

`bridge-link-source.png` ist die Originalgrafik des aktuellen Icons: Rechner und Server auf einer Insel mit einer Verbindung über den Fluss.

`node tools/icon/build_icon.js` erzeugt daraus die 768-px-Android-Ebene mit 108-dp-Leinwand und die 512-px-Storegrafik. Android zeigt die mittleren 72 dp; dort bleibt die Originalgrafik pixelgenau. Der äußere Rand erweitert nur ihre Hintergrundfarben für den Parallax-Effekt.

Die Skripte benötigen Node.js, `sharp` und für `generate.js` zusätzlich `playwright`. `generate.js` erzeugt die sieben älteren Entwürfe in `docs/icon-explorations/` und ruft danach `build_icon.js` auf.
