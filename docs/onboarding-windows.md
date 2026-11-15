# Setting up a development environment on Windows (WSL2 + Ubuntu)

The system runs on Linux. On Windows, do **everything inside WSL2 (Ubuntu)**: code, build, run the local cluster. Windows only runs Docker Desktop and the editor.
It takes about 30-45 minutes the first time. When done, continue at [onboarding.md](onboarding.md).

> Using macOS or real Ubuntu? Skip this file and read [dev/README.md](../dev/README.md).

## What you will have when done
- Ubuntu 24.04 in WSL2 with JDK 21, Maven 3.9, Python 3, git, make and GitHub CLI.
- Docker Desktop usable from Ubuntu.
- VS Code opening the folder inside Ubuntu directly (type `code .`), with the Java and Python extensions.
- The repo cloned in a Linux directory (`~/work`), not on the `C:` drive.

## Machine requirements
Windows 10 (version 2004 or later) or Windows 11, **16 GB RAM**, 25 GB free disk, virtualization enabled in BIOS.

---

## Fast path: run the script (recommended)

### Step 1. Install WSL2, Ubuntu, Docker Desktop, VS Code (on Windows)
You need a copy of `dev/windows/setup-windows.ps1`. If you cannot clone the repo yet (it is private), ask the SA for the file, or follow the [manual path](#manual-path-if-the-script-fails) below.

Open **PowerShell** and run:
```powershell
powershell -ExecutionPolicy Bypass -File .\setup-windows.ps1
```
Script actions: enable WSL2, install Ubuntu 24.04, create `.wslconfig` (WSL RAM limit), install Docker Desktop and VS Code via `winget`, install extensions.
If Windows asks you to **restart**, restart and run the script again (running it multiple times is safe).

### Step 2. Open Ubuntu for the first time
Open **Ubuntu 24.04** from the Start Menu. On first launch it asks for a Linux username and password: choose anything (this password is for `sudo` and is not displayed as you type).

### Step 3. Enable Docker for Ubuntu
Open **Docker Desktop** (accept the terms on first launch) -> **Settings -> Resources -> WSL integration** -> enable the switch for **Ubuntu-24.04** -> **Apply & restart**.
Check in the Ubuntu terminal: `docker run --rm hello-world` must print the greeting.

### Step 4. Log in to GitHub (inside Ubuntu)
The repo is private, so log in before cloning:
```bash
sudo apt-get update && sudo apt-get install -y git gh
gh auth login          # choose: GitHub.com -> HTTPS -> Yes (authenticate git) -> Login with a web browser
gh auth setup-git
```
Follow the verification code shown and open the browser. Your account must have been invited to the repo by the SA (check your invitation email).

### Step 5. Clone the repo and run the setup script (inside Ubuntu)
```bash
mkdir -p ~/work && cd ~/work
git clone https://github.com/<owner>/smart-hospital-platform.git
cd smart-hospital-platform
bash dev/scripts/setup-ubuntu.sh
```
(`<owner>` is the SA's GitHub account; ask the SA.)
The script installs JDK 21, Maven 3.9, Python 3 and make, configures git (asks for the name and email to use for commits), adds `JAVA_HOME`, then checks Docker. **Close and reopen the terminal** for the environment variables to take effect.

### Step 6. Try running the whole system
```bash
cd ~/work/smart-hospital-platform
make -C dev quickstart        # 10-15 minutes the first time
make -C dev apps              # every pod must be Running
```
Check details and the daily work loop: [dev/README.md](../dev/README.md).

### Step 7. Open the code in VS Code
```bash
cd ~/work/smart-hospital-platform
code .
```
The bottom-left corner of VS Code must show **"WSL: Ubuntu-24.04"**. VS Code's integrated terminal is the Ubuntu terminal: run `mvn`, `make` and `git` there.
IntelliJ IDEA: use Ultimate, **File -> Open** the path `\\wsl$\Ubuntu-24.04\home\<user>\work\smart-hospital-platform`, or JetBrains Gateway.

---

## Manual path (if the script fails)
1. PowerShell **Run as administrator**: `wsl --install -d Ubuntu-24.04`, restart the machine, open Ubuntu and create the account.
2. Verify it is WSL2: `wsl -l -v` must show `VERSION 2` on the Ubuntu line. If it is 1: `wsl --set-version Ubuntu-24.04 2`.
3. Install Docker Desktop (https://www.docker.com/products/docker-desktop/) and enable WSL integration as in step 3.
4. Install VS Code (https://code.visualstudio.com/) and the **WSL** extension (`ms-vscode-remote.remote-wsl`).
5. Create the file `C:\Users\<you>\.wslconfig`:
   ```ini
   [wsl2]
   memory=10GB
   processors=6
   swap=4GB
   ```
   then run `wsl --shutdown` in PowerShell to apply it.
6. Continue from step 4 above.

## Common problems
| Symptom | Cause and fix |
|---|---|
| `docker: command not found` in Ubuntu | WSL integration is not enabled for Ubuntu (step 3), or Docker Desktop is not running. |
| `make doctor` reports "Windows is reserving some ports ..." | Windows reserves some port ranges automatically. Open PowerShell as Admin: `net stop winnat; net start winnat`, then try again. |
| `bad interpreter: /usr/bin/env bash^M` | The repo was cloned on the Windows side (CRLF). Delete it and clone again **inside Ubuntu** (`~/work`). |
| Slow build, file permission errors | The repo is under `/mnt/c/...`. Clone into `~/work` inside Ubuntu. |
| Pod `OOMKilled`, machine unresponsive | Increase `memory` in `.wslconfig` (at most about machine RAM - 4 GB), run `wsl --shutdown`, and increase RAM in Docker Desktop. |
| `Permission denied (publickey)` during `git clone` | Use the HTTPS link as instructed and run `gh auth login`; or you have not been invited to the repo. |
| Missing `docker buildx` | Docker Desktop includes it. If you installed Docker Engine yourself: `sudo apt-get install docker-buildx`. |
| `wsl --install` reports a virtualization error | Enable Virtualization (Intel VT-x / AMD-V) in BIOS. |

## Daily work
- Always open the **Ubuntu** terminal (or a VS Code terminal in WSL mode), not PowerShell, for `make`/`mvn`/`git`.
- Stop the cluster when not in use to save resources: `make -C dev down`. Shut down WSL completely: `wsl --shutdown`.
- Do not edit repo files with Windows programs through `\\wsl$` if they convert line endings to CRLF; use VS Code in WSL mode.
