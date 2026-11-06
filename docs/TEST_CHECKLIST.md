# Manual test checklist — Cloak Room (offline phase)

Tick each box on a real phone or POS device. Every test says **what to do** and **what you should see**.
If something is different, note the test number and what you saw.

## 0. Before you start

- [ ] **0.1 Install.** Phone connected by USB, in the project folder run `.\gradlew installDebug` (PowerShell) — or press **Run ▶** in Android Studio.
- [ ] **0.2 Clean start.** Log in, open ⋮ → **Reset demo data** → Reset (debug builds only; also on the Login screen's ⋮). You are logged out.
- Demo logins after a reset:

  | User ID | Password | Role | Code |
  |---|---|---|---|
  | `admin` | `Admin@123` | Admin | U01 |
  | `user1` | `User@123` | Counter user | U02 |

- Demo rates: ₹50 per bag for the first 24 h, ₹20 per bag for every further 24 h, GST 18 % included.

---

## 1. Login, password and session

- [ ] **1.1** Log in as `user1` / `User@123` → "Change password" screen with "This is your first login…". No way back.
- [ ] **1.2** New password `short` → "The password must have at least 8 characters."
- [ ] **1.3** New password = current password → "must be different". Mismatched confirm → "do not match".
- [ ] **1.4** Set `Counter@1` → Dashboard. Logout, log in with `User@123` → refused; with `Counter@1` → Dashboard, no password screen.
- [ ] **1.5** Type `USER1` in capitals → logs in (user IDs are not case sensitive).
- [ ] **1.6** Wrong password → "Wrong user ID or password. 4 attempts left." Unknown user ID → same message without a count of attempts for a real user.
- [ ] **1.7** 5 wrong passwords for `user1` → "Too many wrong passwords. This user is now blocked." Right password now → "This user is blocked."
- [ ] **1.8** Close the app fully (swipe away) and reopen → still logged in, straight to the Dashboard.
- [ ] **1.9** Change password from the Dashboard ⋮ menu works and returns to the Dashboard.

## 2. Users (admin)

- [ ] **2.1** As admin, Dashboard → **Users**: both users listed with code, role, Active/Blocked, "Logged in since …" or "Not logged in".
- [ ] **2.2** Unblock `user1` (from test 1.7) → Active, wrong-password count gone; `user1` can log in again.
- [ ] **2.3** **Add user** `counter2`, name "Counter Two", Counter user, first password `First@123` → appears as **U03**, "Must change password at next login".
- [ ] **2.4** Add `counter2` again → "already exists". Add `ab` → "User ID must be 3–20 characters…".
- [ ] **2.5** **Edit** your own admin row → the user-type chips are disabled. Your own row has no Block / Force Logout buttons.
- [ ] **2.6** **Reset password** of `user1` to `Reset@123` → user1 must change it at next login.
- [ ] **2.6a** **Login shift:** Edit `user1` → Login shift → choose a shift that is **not** now (e.g. Morning in the evening) → Save. The card shows "Login shift: Morning (06:00–14:00)". Log out and log in as `user1` → "You can log in only during your shift Morning (06:00–14:00)."
- [ ] **2.6b** Set `user1` to the shift that covers the current time → login works. Within 15 minutes before a shift starts, login also works (Settings → General → Early login).
- [ ] **2.6c** Settings → General → Login only in own shift **off** → `user1` can log in at any time again. Set it back on.
- [ ] **2.6d** Set `user1` to **Any time** → can log in at any time. Admin rows have no Login shift choice.
- [ ] **2.7** Your own row says "Logged in since <time of your login>"; every user who logged out says "Not logged in".
- [ ] **2.8** **Single session and Force Logout — not testable by hand yet.** In this offline phase each phone has its own database, and one phone can only have one user logged in, so "Already logged in since <time>. Logout first." and Force Logout of *another* logged-in user can only happen once several devices share data through the server. Both rules are covered by the unit tests in `AuthRepositoryTest` (run `.\gradlew testDebugUnitTest`).
- [ ] **2.9** As `user1`: no **Users**, **Settings** or **Masters** buttons on the Dashboard.

## 3. Settings (admin)

- [ ] **3.1** Dashboard → **Settings**: tabs Billing / General / Print; switches, number fields with "Allowed: …", dropdowns, a masked password field.
- [ ] **3.2** Turn **GST applicable** off → GST %, GST type, Rate includes GST are greyed out with "Turn on "GST applicable" to change this."
- [ ] **3.3** Print → Print copies = `5` → red "Must be at most 3", red **1** on the Print tab, Save disabled.
- [ ] **3.4** Billing → First block rate `60` → **Save 1 change** → dialog shows "50 → 60" → Save → "1 setting saved." Dashboard tariff card now says ₹60.
- [ ] **3.5** Change something, press back → "Discard changes?".
- [ ] **3.6** General → Report password on without typing one → "Set a password". Type one and save → "A password is set".
- [ ] **3.7** Set everything back (or Reset demo data) before the billing tests.

## 4. Check-in

- [ ] **4.1** Dashboard → **Check-in**. Fields marked * follow the settings (PNR, ID proof).
- [ ] **4.2** Save an empty form → red messages under PNR, name, mobile, ID proof, ID number; nothing saved.
- [ ] **4.3** Mobile `12345` → "Enter a valid 10-digit mobile number." Bags `0` or `11` → bag message (max 10).
- [ ] **4.4** Fill PNR `4512345678`, name `Ravi Kumar`, mobile `9876543210`, 2 bags (use **+**), Aadhaar Card, `1234 5678 9012` → **Save & print receipt** → receipt preview.
- [ ] **4.5** Receipt shows: header lines, "LUGGAGE RECEIPT", **U02-00001** (user1) or **U01-00001** (admin), date and time in IST, name, mobile, PNR, Bags 2, **ID Aadhaar Card XXXX9012**, the rate, "Payment at check-out.", a **QR**, the footer.
- [ ] **4.6** Scan the receipt QR with any QR app: it shows short JSON with the receipt number, name, mobile, PNR — **no ID number**.
- [ ] **4.7** On a phone or emulator with no printer: **Print** → "No printer found. Turn on Bluetooth and the printer, pair it…, or use Share PDF." **Share PDF** → share sheet; the PDF looks like the preview. (On the GC039 POS see section 9.)
- [ ] **4.8** **New check-in** → empty form; next receipt is **…-00002**.
- [ ] **4.9** Settings: Print QR off → no QR on the next receipt. Paper width 80 mm → wider receipt. PNR required off → PNR no longer has *.
- [ ] **4.10** Settings: Mask ID number off → next receipt shows the full ID number.

## 5. Check-out and bill

- [ ] **5.1** Dashboard → **Check-out** → type `9876` → the bag appears, "In store". Tap it.
- [ ] **5.2** Bill for 2 bags just checked in: Rent 100.00, Taxable value 84.75, CGST 9 % 7.63, SGST 9 % 7.62, **TOTAL ₹100.00**, "GST included in the rate".
- [ ] **5.3** Penalty `600` → "Penalty cannot be more than ₹500.00", buttons disabled. Penalty `20` → TOTAL ₹120.00.
- [ ] **5.4** **UPI** → payment QR "Scan to pay ₹…". Scan it with GPay/PhonePe on another phone: payee "Demo Cloak Room", the exact amount. **Do not pay** (sample UPI ID).
- [ ] **5.5** **Bill Print** → "Collect ₹120.00 by UPI?" → Confirm → bill preview: "TAX INVOICE", GSTIN, Bill No **…-00001**, receipt no, In/Out times, Time stored, rent lines, Penalty, CGST/SGST, TOTAL Rs.120.00, Paid by UPI.
- [ ] **5.6** Search the same mobile again → "Out · <bill no>". Tap → "already checked out" + **View bill** shows the same bill.
- [ ] **5.7** Check in another bag → Check-out → **Out Without Bill Print** → bill saved and shown, not sent to the printer. (Reports later show "not printed".)
- [ ] **5.8** **Scan receipt QR**: allow the camera, point at a receipt QR (on another screen or printed PDF) → opens that bag directly. Scan any other QR → "This QR is not a cloak room receipt."
- [ ] **5.9** Settings: UPI reference required on → choosing UPI without a reference → "Enter the UPI reference number."
- [ ] **5.10** Settings: Daily bill limit on, Max bills per day `1` → the user's second bill today → "Daily bill limit reached".
- [ ] **5.11** Settings: Allow out without print off → the button disappears. Allow penalty off → no penalty field.
- [ ] **5.12** Change First block rate after a bill, then **View bill** of the old bill → still the old amounts.

## 6. Dashboard, bags in store, reports

- [ ] **6.1** Dashboard numbers change at once after each check-in and check-out.
- [ ] **6.2** As `user1`: "Today (you)" — only user1's check-ins/bills/collection. As admin: "Today (all users)".
- [ ] **6.3** Settings: Show total collection off → no Collection card on the Dashboard.
- [ ] **6.4** Tap **Bags in store now** → list (all users, oldest first) with search; **Check out** opens the bill; **Reprint receipt** shows the receipt again.
- [ ] **6.5** **Reports** as `user1`: today's own check-ins (by who checked in) and bills (by who billed); Collection tab: Cash and UPI cards with rent, penalty, taxable, CGST, SGST, IGST, collected; Total.
- [ ] **6.6** Reports as admin: User dropdown "All users" / U01 / U02 filters the lists.
- [ ] **6.6a** **Shift** dropdown (everyone): "All shifts", Morning, Evening, Night. Choose Morning → only check-ins made 06:00–14:00 and bills made 06:00–14:00; each row shows its shift.
- [ ] **6.6b** Check a bag in during one shift and out during the next (or change the shift times in Masters to test quickly) → it is a check-in of the first shift and a bill of the second.
- [ ] **6.6c** Remove a shift in Masters → Reports still lists it as "· removed". Share PDF prints the chosen shift under the user line.
- [ ] **6.7** From/To pickers: a date range with no activity → "No check-ins in this period."
- [ ] **6.8** Report **Share PDF** → "COLLECTION REPORT" with period, user, totals and one line per bill.
- [ ] **6.9** Settings: Users see own reports off → `user1` has no Reports button.
- [ ] **6.10** Settings: Report password on → opening Reports asks for it; a wrong one → "Wrong report password."

## 7. Masters (admin)

- [ ] **7.1** Dashboard → **Masters** → four tabs: Cloak room, ID proofs, Header / footer, Shifts.
- [ ] **7.1a** **Cloak room:** name, address, PIN code, GSTIN and UPI ID can be edited; Mobile, Email, Renewal date and User limit are under "Set by Synergic" and cannot be edited.
- [ ] **7.1b** PIN `71110` → Save stays disabled; GSTIN `19ABC` → "GSTIN must be 15 characters…"; UPI `nobank` → "UPI ID must look like name@bank."
- [ ] **7.1c** Change the UPI ID to `test@upi` → Save → at check-out choose UPI: the payment QR now pays `test@upi`.
- [ ] **7.1d** Clear the UPI ID → Save → check-out with UPI shows "No UPI ID is set…" instead of a QR. Clear the GSTIN → new bills have no GSTIN line.
- [ ] **7.2** **ID proofs:** Add "Ration Card" → shows in the Check-in dropdown. Add "ration card" again → "already exists". Rename, then **Remove** → gone from the dropdown; an old receipt with it still shows the name.
- [ ] **7.3** **Header / footer:** Edit the footer text → next receipt and bill show the new text.
- [ ] **7.4** Add a Footer line with "On check-in receipt" off → appears on bills but not on receipts.
- [ ] **7.5** Move the second header **up** → the two header lines swap on the next receipt.
- [ ] **7.6** Add a **Logo**: Choose picture (any photo or logo) → black-and-white preview → Save → the logo prints at the top of receipts/bills/reports as switched on.
- [ ] **7.7** Remove a line → gone from new receipts.
- [ ] **7.8** **Shifts:** Add "Early" `4:30` – `6:00` → saved as 04:30 – 06:00. `25:00` → "From time must be like 06:00 (24-hour clock)."
- [ ] **7.9** Night 22:00 – 06:00 shows "(next day)".
- [ ] **7.10** As `user1`, the Masters button is not shown.

## 8. General

- [ ] **8.1** Rotate the phone on Check-in, Check-out and Receipt screens → typed values stay, nothing is saved twice.
- [ ] **8.2** Turn on aeroplane mode → everything still works (offline app).
- [ ] **8.3** Install on an Android 7 (API 24) device or emulator if available → the app opens and check-in/check-out work.
- [ ] **8.4** Debug: Dashboard ⋮ → **Database status** shows row counts; after a check-out `td_luggage_in_out` and `td_receipt` counts went up.
- [ ] **8.5** Optional (Android Studio): App Inspection → Database Inspector → `td_luggage_in_out`: after check-out the row has `out_flag = Y` and `sync_flag = N`; `td_receipt` has the rates, GST % and amounts; `md_user.password_hash` starts with `pbkdf2_sha256$`.

## 9. Printing on the GC039 / GC039ES POS

Install the debug APK on the POS (USB cable + Android Studio ▶, or copy the APK). Load a paper roll.

- [ ] **9.1** Dashboard → Printer setup → **Printer type** shows Auto / Bluetooth / Built-in (GC039) / USB (GC039ES) / None; default **Auto**. Settings → Print no longer shows Printer or Bluetooth printer.
- [ ] **9.2** Check in a bag → **Save & print receipt** → the receipt prints by itself: header, ₹ amounts (not "?"), the QR, footer, then about 2.5 cm of blank paper to tear off. It looks like the preview on screen.
- [ ] **9.3** Scan the printed QR at Check-out → the bag opens.
- [ ] **9.4** **Bill Print** → the bill prints with GST lines and TOTAL. **Out Without Bill Print** → nothing prints.
- [ ] **9.5** Print copies = 2 → two receipts, one after the other.
- [ ] **9.6** Take out the paper roll → **Print** → "The printer is out of paper. Load a roll and try again." Put it back → Print works.
- [ ] **9.7** Add a logo in Masters → it prints at the top, black and white.
- [ ] **9.8** Reports → print → the report prints.
- [ ] **9.9** Printer setup → Printer type **None** → Print shows "No printer found…", nothing prints. Set it back to Auto.
- [ ] **9.10** GC039ES only: the first print asks "Allow Cloak Room to access the USB device?" → OK → it prints. If Auto does not print, set Printer = **USB (GC039ES)** and try again.
- [ ] **9.11** If nothing prints on the GC039: set Printer = **Built-in (GC039, serial)** → Print → note the exact message shown and send it to the developer.

## 10. Bluetooth printer

- [ ] **10.1** Switch the printer on. Android Settings → Bluetooth → pair it (PIN is usually `0000` or `1234`).
- [ ] **10.2** Open the app (Android 12+): "Allow Cloak Room to find, connect to… nearby devices?" → **Allow**.
- [ ] **10.3** Check in a bag → the receipt prints on the Bluetooth printer with ₹ amounts and the QR, same as the preview.
- [ ] **10.4** **Print** on a bill and on Reports → prints.
- [ ] **10.5** Switch the printer off → **Print** → "Could not reach the printer "…". Check that it is on and near…". Switch on → Print works again.
- [ ] **10.6** Turn Bluetooth off → Print → "No printer found…" (Auto) or "Bluetooth is off…" (Printer = Bluetooth).
- [ ] **10.7** If it does not print: Dashboard → Printer setup → pick the printer → **Test print** → **Use this printer**.
- [ ] **10.8** If "Nearby devices" was refused: Print shows how to allow it; allow it in Android Settings → Apps → Cloak Room → Permissions → Print works.
- [ ] **10.9** Admin: Dashboard → **Printer setup** → the paired devices are listed (printers first, with a printer icon).
- [ ] **10.10** **Test print** on your printer → a "PRINTER TEST" page prints; the digit row reaches the right edge, ₹70.00 and the QR are clear.
- [ ] **10.11** **Use this printer** → "… is now the printer"; the card turns blue with a tick; Current printer shows Bluetooth and its name. Check in a bag → the receipt prints on it.
- [ ] **10.12** Type a wrong address `12:34` → **Test print** → "Enter the address like 00:11:22:AA:BB:CC".
- [ ] **10.13** Turn Bluetooth off → come back to Printer setup → "Bluetooth is off" with **Open Bluetooth settings**.
- [ ] **10.14** As `user1`: the **Printer setup** button is shown (above the Admin buttons, which stay hidden). The card says "Your choice here applies to your login only."
- [ ] **10.15** As `user1`: **Test print** → prints. **Use this printer** → "… is now your printer". Check in a bag → the receipt prints on it.
- [ ] **10.16** Log out, log in as `admin` → Printer setup shows the admin's own (default) printer, not user1's. Settings stays admin-only for user1.
- [ ] **10.17** As `user1`: a **Settings** button is beside Reports → opens Settings with only the **Print** tab and the note "Changes here apply to your login only."
- [ ] **10.18** As `user1`: Print copies = 2 → Save → the next receipt prints twice. Print copies = 5 → red "Must be at most 3", Save disabled.
- [ ] **10.19** Log in as `admin` → Settings → Print still shows Print copies = 1 (the default), and all three tabs.

