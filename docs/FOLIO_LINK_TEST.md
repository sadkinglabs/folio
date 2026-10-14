# Folio Link: phone test

This answers one question before more of M3 is built on it: can KOReader reach Folio on your phone while the phone's screen is off?

## Set up (once)
1. Install the APK. Open **Settings → Connections → Folio Link** and turn it on.
2. Note the address (for example `http://192.168.1.20:8765`), the username `folio` and the password (tap **Show password**).
3. On the e-reader, open a book that you sent from Folio. It must be the same file.
4. In KOReader: **Tools → Progress sync → Custom sync server**, enter the address. Then **Register** or **Login** with the username and password.
5. In the same menu, turn on automatic sync if you want KOReader to push on page turns and pull on open.

## Test A: Wi-Fi, screen off
1. Phone and e-reader on the same Wi-Fi. Folio Link on, **Keep the phone awake** off.
2. Lock the phone. Wait 30 minutes.
3. On the e-reader, turn a few pages, then **Progress sync → Push progress now**, then **Pull progress now**.
4. Unlock the phone. In Folio Link, tap **Show connection log**.

Record: did the requests arrive while the screen was off? The log shows "Phone screen off", then the e-reader's requests with times.

## Test B: hotspot, screen off
Same as A, with the e-reader on the phone's hotspot. The address changes; use the one Folio shows for the hotspot.

## Test C: if A or B failed
Turn on **Keep the phone awake** and repeat the test that failed.

## Test D: positions both ways
1. Read a few pages on the phone in Folio's reader. Close it.
2. Open the same book on the e-reader. KOReader should offer to jump to the phone's place.
3. Read on the e-reader, push progress, then open the book in Folio. Folio should offer "Continue from … at N%?".

## Report back
For each test: passed or failed, the phone model and Android version, and a screenshot of the connection log.
