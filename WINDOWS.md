# Running it on Windows with VS Code

The full guide is in `RUNBOOK.md`. This is the same thing with Windows paths, PowerShell
commands and the VS Code setup, plus the two problems that only happen on this stack.

---

## 1. Install

Open **PowerShell as administrator** and use winget, which saves hunting for installers:

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
winget install Apache.Maven
winget install OpenJS.NodeJS.LTS
winget install MongoDB.Server
winget install Git.Git
```

Close that window and open a **new** PowerShell, so the PATH changes take effect. Then
check all four:

```powershell
java -version     # 17 or newer
mvn -version
node -v           # 20 or newer
mongod --version
```

Start MongoDB. The installer registers it as a service:

```powershell
net start MongoDB
```

If that says it is already running, good. Confirm it is actually answering:

```powershell
Test-NetConnection localhost -Port 27017
```

`TcpTestSucceeded : True` is what you want. Nothing downstream works without it.

---

## 2. Open the project

```powershell
cd $HOME\Downloads
Expand-Archive pib-lms.zip -DestinationPath .
cd pib-lms
code .
```

VS Code will offer the recommended extensions on first open. Accept them, or install
these by hand:

| Extension | Why |
|---|---|
| **Extension Pack for Java** | Language support, Maven, debugging |
| **Spring Boot Extension Pack** | Run and debug the API properly |
| **ESLint** | Frontend |
| **MongoDB for VS Code** | Browse the database without another tool |

Give the Java extension a minute on first open. The status bar shows when it has finished
importing the Maven project; starting anything before that is what causes half-built
class files.

---

## 3. Run it

### The easy way

```powershell
.\run.ps1
```

That checks Mongo, builds the backend, waits for the health endpoint, then starts the
frontend. Ctrl-C stops both.

If PowerShell refuses to run the script:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

That lasts for the current window only.

### From inside VS Code

**Run and Debug** in the sidebar, pick **Everything**, press F5. It starts the frontend
task, launches the API with the right environment variables, and opens a browser. Set
breakpoints in Java and they work.

To run pieces separately, **Ctrl-Shift-P → Tasks: Run Task**:

| Task | What it does |
|---|---|
| `backend: build` | `mvn clean package` |
| `backend: clean target` | Deletes compiled classes. The fix for a broken IDE build |
| `frontend: install` | `npm install` |
| `frontend: dev` | Vite on 5173 |
| `checks: all` | The offline checkers, faster than a build |

### By hand, two terminals

VS Code's terminal splits with the icon in its top right.

```powershell
# terminal one
cd backend
mvn clean package
java -jar target\pib-lms-1.0.0.jar
```

```powershell
# terminal two
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**.

---

## 4. Sign in

The database has no accounts until you create the first one:

```bash
BOOTSTRAP_EMAIL=you@proitbridge.com
BOOTSTRAP_PASSWORD=something-long-and-temporary
```

That is the only account that exists. It signs in at `/staff`, is made to change its
password immediately, and creates everyone else from inside the product.


Then follow **step 9 of `RUNBOOK.md`** to put your own settings and course in.

---

## The two problems specific to this setup

### "Unresolved compilation problems" at startup

You have already seen this one. It is not a code bug. It means VS Code's Java extension
wrote broken class files into `backend\target\classes` and Spring loaded them. It happens
when the API is started before the project has finished importing.

```powershell
cd backend
Remove-Item -Recurse -Force target
mvn clean package
```

Then in VS Code: **Ctrl-Shift-P → Java: Clean Java Language Server Workspace → Restart
and delete**. That clears the extension's own cache, which is usually the thing still
holding the old state.

### PowerShell environment variables

They are not `export`. For the current window:

```powershell
$env:JWT_SECRET = "a-long-random-string-at-least-32-characters"
$env:MONGODB_URI = "mongodb://localhost:27017/pib_lms"
```

Permanently, for your user:

```powershell
[Environment]::SetEnvironmentVariable('JWT_SECRET', 'your-real-secret', 'User')
```

Then open a new terminal. A secret under 32 bytes fails at startup with a message saying
so, rather than something cryptic from the JWT library.

---

## Other things that come up on Windows

| Symptom | Fix |
|---|---|
| `mvn` not recognised | Open a new terminal. winget updates PATH but not the window you installed from |
| Port 8080 already in use | `Get-NetTCPConnection -LocalPort 8080` then `Stop-Process -Id <pid>` |
| Port 5173 in use | An old Vite is still running. Same two commands with 5173 |
| `net start MongoDB` says access denied | The terminal is not administrator |
| Line endings look wrong in Git | `git config --global core.autocrlf true` |
| Long path errors during `npm install` | `git config --system core.longpaths true`, as administrator |
| Uploads fail over 25 MB | Raise `FILES_MAX_BYTES` and the multipart limits in `application.yml` together |

---

## Docker on Windows, if you would rather

Install Docker Desktop, make sure it is running, then from the project root:

```powershell
docker compose up --build
```

Mongo, the API and the frontend all come up together. Open **http://localhost:8081**.
`docker compose down -v` wipes the data and gives you a clean seed next time. This path
avoids installing Java, Maven, Node and MongoDB entirely, which is worth considering if
this machine is only ever going to run the project rather than develop it.
