# OrbixPOS {{VERSION}} (Windows x64)

Build: {{BUILD}}

OrbixPOS is the till (point-of-sale) app for OrbixERP. It runs on the till PC and talks to
your OrbixERP server over the network. Prices, VAT, stock and totals all come from the
server; the till holds no price list of its own.

This guide is for whoever installs and looks after the tills. Cashiers and supervisors: see
`Docs\OrbixPOS-User-Manual.docx` for day-to-day use.

**Contents**

1. What is in this folder
2. What you need
3. Before you install: set up the ERP (once per shop)
4. Installing on a till PC
5. First run: server setup and receipt printer
6. HTTPS: when the server uses its own certificate
7. Upgrading to a new version
8. Where the till keeps its settings
9. Troubleshooting
10. Getting help
11. What is new in this version

---

## 1. What is in this folder

| File | What it is |
|---|---|
| `pos_app.exe` | The till app. Start this. |
| `flutter_windows.dll`, `data\`, other `.dll` files, `native_assets.json` | Parts of the app. Keep them next to `pos_app.exe`; do not move or delete them. |
| `README.txt` | This guide. Opens in Notepad. |
| `Docs\OrbixPOS-User-Manual.docx` | The user manual for cashiers, supervisors and managers. Opens in Word, LibreOffice or WordPad. |
| `Docs\OrbixPOS-User-Manual.txt` | The same manual as plain text, for a PC without Word. |
| `Docs\RELEASE-NOTES.txt` | What changed in every version. |

Every guide also comes as a Markdown file with the same name, for anyone who prefers a
Markdown viewer; the text is identical.

---

## 2. What you need

**Till PC**

- Windows 10 or Windows 11, 64-bit.
- The Microsoft Visual C++ Redistributable (x64), 2015-2022. Most PCs already have it. If
  `pos_app.exe` will not start and Windows says `MSVCP140.dll` or `VCRUNTIME140.dll` was not
  found, install it from Microsoft: search for "Visual C++ Redistributable latest supported
  downloads" and install the **X64** version.
- A network connection to the OrbixERP server. A cable is better than Wi-Fi at a till.

**Receipt printer** (optional, but normal)

- Installed in Windows with its driver, so it appears under *Settings > Bluetooth & devices >
  Printers & scanners*.
- 58 mm or 80 mm paper. 80 mm is easier to read on a long basket.
- Thermal printers that understand ESC/POS (most of them) also cut the paper and can open the
  cash drawer.

**Cash drawer** (optional)

- Plugged into the receipt printer's drawer port (the small RJ11/RJ12 socket). The till opens
  it through the printer after a receipt.

**Barcode scanner** (optional)

- Any USB scanner working as a keyboard (the factory default for most), set to send **Enter**
  after each code. Test it in Notepad first: a scan should type the digits and move to a new
  line.

---

## 3. Before you install: set up the ERP (once per shop)

An administrator does this in the OrbixERP web app. A till cannot sell until all of it is in
place.

1. **Products and prices.** The items to sell exist, are active, and have a price on the
   price list the branch sells from. An item with no price cannot be rung up.
2. **A till for the branch.** *Point of Sale > POS Tills*: add a till for each counter (for
   example "Till 1"). Till names must be unique within a branch.
3. **A user for each cashier.** *Administration > Users*: create the user, assign the branch
   (make it the user's default branch) and give the role **Cashier**. Every cashier needs
   their own login. Do not share logins: every sale, refund and drawer count is recorded
   against the person signed in.
4. **A sales-agent record for each cashier.** *Parties > Sales Agents*: add an **INTERNAL**
   agent and link it to the cashier's user. Without it the till refuses to post sales.
5. **At least one manager.** A user with the **Branch Manager** role (or another role that
   holds the same approvals). The till asks a manager to approve refunds, the X-read and
   Z-read drawer reports, and discounts larger than a cashier may give alone. The manager
   types their own username and password at the till; the cashier stays signed in.

> The super-administrator account cannot sell at a till. Use a real cashier account.

---

## 4. Installing on a till PC

1. Make a folder, for example `C:\OrbixPOS`.
2. Unzip everything in this zip into it, keeping the folder structure (right-click the zip >
   *Extract All*).
3. Optional: right-click `pos_app.exe` > *Send to* > *Desktop (create shortcut)*, so the
   cashier can start the till from the desktop.
4. Start `pos_app.exe`.

Windows may show "Windows protected your PC" the first time. Click *More info* and then *Run
anyway*. This happens because the app is not signed with a commercial certificate; it does
not mean anything is wrong.

There is no installer and nothing is written to Program Files. To remove the till, delete the
folder (and see section 8 for the settings).

---

## 5. First run: server setup and receipt printer

On the sign-in screen click **Server setup** (below the *Sign in* button). The *Setup &
diagnostics* window opens.

### ERP host

Type the address of your OrbixERP server: the scheme and the host (and port) **only**, with
nothing after it. The app adds `/api/v1` itself.

| Address | When |
|---|---|
| `http://192.168.1.10:8080` | OrbixERP server installed with the standard server bundle (port 8080) |
| `http://192.168.1.10:8081` | OrbixERP installed as a Windows service on a shop PC (port 8081) |
| `https://erp.mycompany.co.tz` | A server with HTTPS turned on |
| `http://localhost:8081` | The server is on this same PC (the box may already show this) |

Use the same address people type in the browser to open OrbixERP, without anything after the
host or port - your administrator or supplier knows it. Do **not** add `/api/v1` or any other
path; the app adds `/api/v1` itself and the test fails if it is there twice.

Click **Test connection**. You should see "Reachable - ERP is UP." If you see "Could not
reach the ERP at this host.", see section 9.

### Receipt printer

| Setting | What to choose |
|---|---|
| Printer | The receipt printer, from the list. |
| Paper width | 58 mm (32 columns) or 80 mm (48 columns) - match the paper in the printer. |
| Print mode | "Thermal (ESC/POS + cut)" for a thermal receipt printer; "Plain text" only for a printer that prints garbage in thermal mode. |
| Open cash drawer after printing | Tick it if a cash drawer is connected to the printer (thermal mode only). |

Click **Test print**. The test slip shows two amounts that must line up flush on the right
edge. If they wrap or run off the edge, the paper width does not match the paper.

Click **Save**. Then sign in with the cashier's username and password and open the shift (the
user manual explains opening a shift).

The setup window is only on the sign-in screen. To change the server or the printer later,
sign out first.

---

## 6. HTTPS: when the server uses its own certificate

Skip this section if the ERP host starts with `http://`, or if your server has a real
certificate from a public authority (the browser shows a padlock without any warning).

If the server uses a self-signed certificate (the browser warns that the connection is "not
private"), the till refuses to connect until it is told to trust that server. *Test
connection* then says "Could not reach the ERP at this host." Clicking through a warning is
not possible in the till, on purpose.

To trust the server:

1. **Get the server's certificate authority file.** On a standard OrbixERP server with HTTPS
   turned on, the server administrator runs:

   ```
   docker exec orbixerp-caddy cat /data/caddy/pki/authorities/local/root.crt > orbixerp-ca.crt
   ```

   For any other setup, ask your supplier for the server's CA certificate. It must be a text
   file that starts with `-----BEGIN CERTIFICATE-----`.
2. **Put it next to the app.** On the till PC, make a folder named `certs` next to
   `pos_app.exe` (for example `C:\OrbixPOS\certs`) and copy the file into it. The name does
   not matter as long as it ends in `.crt` or `.pem`. One file per server is fine.
3. **Restart the till.** Close OrbixPOS completely and start it again. The till reads the
   certificates only when it starts.
4. **Use the right name.** In *Server setup*, the ERP host must use the same name the
   certificate was made for - usually the name, not the IP address.

The certificate check stays fully on. The till trusts only the public authorities and the
files in the `certs` folder.

If the server's certificate store is ever lost and recreated, every till stops connecting at
once. Repeat the steps above with the new file.

---

## 7. Upgrading to a new version

1. Ask each cashier to finish the sale in progress. A shift can stay open; the till resumes
   it after the upgrade.
2. Close OrbixPOS.
3. Unzip the new version over the old folder, replacing the files. Keep the `certs` folder.
4. Start `pos_app.exe`. The server address, printer settings and sign-in are kept.
5. Do a test sale and print a receipt before the shop gets busy.

Check that the version in this guide's title is the one you meant to install. To go back,
unzip the previous version's zip over the folder the same way.

The till and the ERP server are upgraded separately. Your supplier tells you when a new till
version needs a newer server.

---

## 8. Where the till keeps its settings

Settings are stored for the Windows user, not in the app folder:

```
%APPDATA%\net.otapp\Orbix POS\shared_preferences.json
```

(Paste the folder part into the File Explorer address bar to open it.)

That file holds the server address, the printer settings, the sign-in, and the last 50
receipts the till can reprint without the network.

- **Moving a till to a new PC:** install the app, then run *Server setup* again. Shifts and
  sales live on the server, not on the PC.
- **Starting completely fresh:** close OrbixPOS and delete that folder. The till forgets the
  server, the printer and the sign-in; nothing is lost on the server.
- Each Windows user on the same PC has separate settings.

---

## 9. Troubleshooting

### `pos_app.exe` does not start, "MSVCP140.dll was not found"

Install the Visual C++ Redistributable (section 2).

### `pos_app.exe` does not start, nothing happens

Check every file from the zip is in the folder, next to `pos_app.exe`, including the `data`
folder. Unzip again if unsure.

### "Could not reach the ERP at this host."

- Open the same address in a browser on the till PC. If the browser cannot open it either, it
  is a network or server problem: check the cable or Wi-Fi, that the server is on, and the
  firewall.
- Check the address has no `/api/v1` or other path after the host or port.
- `http` vs `https`: use the one the browser uses.
- If the browser shows a certificate warning, see section 6.
- After adding a certificate, the app must be closed and started again.

### Sign-in fails with a message from the server

The message says why (wrong password, user inactive, no branch assigned). Fix it in the ERP
web app (section 3).

### The till signs in but will not open a shift, or will not post a sale

Check section 3: a till for the branch, the cashier's default branch, the Cashier role, and
the linked internal sales agent.

### "You do not have permission for this action."

The signed-in user's role does not include that action. A manager can approve some actions at
the till; others need the role changed in the ERP web app.

### A till shows as in use by someone else

The till names the cashier whose shift is open on it. That cashier can sign in and resume or
close their shift. Otherwise a branch manager closes the session in the ERP web app (*Point of
Sale > POS Sessions*), for example after a PC was switched off mid-shift. The user manual has
the details.

### An item scans but the price is 0.00, or "no price"

The item has no price on the branch's price list. Fix it in the ERP.

### Scanning adds nothing, or the wrong item

- Test the scanner in Notepad (section 2): it must type the code and press Enter.
- Scanning a code that is not set up as a barcode of an item finds nothing. Add the barcode to
  the item in the ERP.
- Versions before 1.5.2 could add the previous item on fast scans; upgrade.

### A sale ends with a question about whether it went through

The network dropped, or the server was still busy, while the sale was being saved. Use the
retry the till offers: it re-sends the **same** sale, so the customer is never charged twice,
and it shows the reason the server gave. The till keeps asking about that sale, even after a
restart, until the server confirms it. If it keeps failing, keep the customer's goods and
payment aside and call a manager.

### "No receipt printer set - configure one in Setup."

Sign out, open *Server setup*, choose the printer, *Save*.

### The receipt prints strange characters, or nothing

- Try print mode "Plain text" if the printer is not a thermal ESC/POS printer.
- Print a Windows test page from the printer's properties. If that fails too, it is the
  printer or its driver.

### The receipt wraps badly or the totals do not line up

The paper width setting does not match the paper (58 mm or 80 mm).

### The cash drawer does not open

Tick "Open cash drawer after printing", use thermal mode, and check the drawer cable is in the
printer's drawer port, not a phone socket.

> **About receipts:** the OrbixPOS receipt is an ordinary sales receipt. It is **not** a TRA
> fiscal (EFD/VFD) receipt and carries no TRA verification code. Issue fiscal receipts the way
> your business does today.

---

## 10. Getting help

When you contact your supplier, send:

- the version and *Build* line from the top of this guide;
- the till name and branch, and the cashier signed in;
- the date and time it happened;
- a photo or screenshot of the message on the screen (the exact words matter);
- for printing problems, the printer make and model and a photo of the receipt.

---

## 11. What is new in this version

{{NOTES}}
