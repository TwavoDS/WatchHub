# WatchHub

Alles-in-één companion voor de Xiaomi Watch S4 (Vela / HyperOS), naast Mi Fitness (band-mod):

- **Camera** – livebeeld van de telefooncamera op het horloge, sluiter met timer, voor/achter, weergave "Vol" of "Heel". Werkt met de eigen camera en met de Xiaomi-camera (schermopname + toegankelijkheidsdienst).
- **Navigatie** – pijl, afstand, straat en aankomsttijd uit de Google Maps-melding; trilt vlak voor een afslag; opent vanzelf.
- **Meldingen** – per app: eigen trilpatroon, kleur, filter op woorden, herhalen tot weggetikt. Algemeen: stille uren en "niet als het telefoonscherm aan staat". Wegtikken op het horloge verwijdert de melding ook op de telefoon.

`android/` is de telefoon-app, `watch/` de horloge-app. Ze praten via Xiaomi's interconnect (Mi Fitness) en zijn met dezelfde sleutel ondertekend.

## Installeren

1. `WatchHub-phone.apk` op de telefoon; geef toegang tot meldingen (eventueel eerst App-info → ⋮ → *Beperkte instellingen toestaan*).
2. `WatchHub-watch.rpk` op het horloge via Mi Fitness → *User apps*, pakketnaam `com.twavods.watchhub`.
3. Zet in Mi Fitness de meldingen uit voor de apps die je in WatchHub aanzet.

Elke push naar `main` bouwt beide bestanden (Releases → latest).
