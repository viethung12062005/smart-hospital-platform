# Cài môi trường làm việc trên Windows (WSL2 + Ubuntu)

Hệ thống chạy trên Linux. Trên Windows, bạn làm **mọi việc bên trong WSL2 (Ubuntu)**: code, build, chạy cụm local. Windows chỉ chạy Docker Desktop và trình soạn thảo.
Mất khoảng 30–45 phút lần đầu. Làm xong rồi tiếp tục ở [onboarding.md](onboarding.md).

> Dùng macOS hoặc Ubuntu thật thì bỏ qua file này, đọc [dev/README.md](../dev/README.md).

## Bạn sẽ có gì sau khi xong
- Ubuntu 24.04 trong WSL2, có JDK 21, Maven 3.9, Python 3, git, make, GitHub CLI.
- Docker Desktop dùng được từ Ubuntu.
- VS Code mở thẳng thư mục trong Ubuntu (gõ `code .`), có extension Java và Python.
- Repo clone trong thư mục Linux (`~/work`), không nằm ở ổ `C:`.

## Yêu cầu máy
Windows 10 (bản 2004 trở lên) hoặc Windows 11, **16 GB RAM**, 25 GB ổ trống, bật ảo hóa (virtualization) trong BIOS.

---

## Cách nhanh: chạy script (khuyên dùng)

### Bước 1. Cài WSL2, Ubuntu, Docker Desktop, VS Code (trên Windows)
Bạn cần một bản copy file `dev/windows/setup-windows.ps1`. Nếu chưa clone được repo (repo private), nhờ SA gửi file này, hoặc làm theo [phần thủ công](#cách-thủ-công-nếu-script-lỗi) bên dưới.

Mở **PowerShell** và chạy:
```powershell
powershell -ExecutionPolicy Bypass -File .\setup-windows.ps1
```
Script làm: bật WSL2, cài Ubuntu 24.04, tạo `.wslconfig` (giới hạn RAM của WSL), cài Docker Desktop và VS Code qua `winget`, cài extension.
Nếu Windows yêu cầu **khởi động lại**, hãy khởi động lại rồi chạy lại script (chạy lại nhiều lần vẫn an toàn).

### Bước 2. Mở Ubuntu lần đầu
Mở **Ubuntu 24.04** từ Start Menu. Lần đầu nó hỏi tên người dùng và mật khẩu Linux: đặt tùy ý (mật khẩu này dùng cho `sudo`, sẽ không hiện ký tự khi gõ).

### Bước 3. Bật Docker cho Ubuntu
Mở **Docker Desktop** (chấp nhận điều khoản ở lần đầu) → **Settings → Resources → WSL integration** → bật công tắc cho **Ubuntu-24.04** → **Apply & restart**.
Kiểm tra trong terminal Ubuntu: `docker run --rm hello-world` phải in ra lời chào.

### Bước 4. Đăng nhập GitHub (trong Ubuntu)
Repo là private nên cần đăng nhập trước khi clone:
```bash
sudo apt-get update && sudo apt-get install -y git gh
gh auth login          # chọn: GitHub.com → HTTPS → Yes (authenticate git) → Login with a web browser
gh auth setup-git
```
Làm theo mã xác thực hiện ra và mở trình duyệt. Tài khoản của bạn phải đã được SA mời vào repo (kiểm tra email lời mời).

### Bước 5. Clone repo và chạy script cài đặt (trong Ubuntu)
```bash
mkdir -p ~/work && cd ~/work
git clone https://github.com/<owner>/smart-hospital-platform.git
cd smart-hospital-platform
bash dev/scripts/setup-ubuntu.sh
```
(`<owner>` là tài khoản GitHub của SA, hỏi SA.)
Script cài JDK 21, Maven 3.9, Python 3, make, cấu hình git (hỏi tên và email dùng cho commit), thêm `JAVA_HOME`, rồi kiểm tra Docker. **Đóng terminal và mở lại** để biến môi trường có hiệu lực.

### Bước 6. Chạy thử toàn bộ hệ thống
```bash
cd ~/work/smart-hospital-platform
make -C dev quickstart        # 10–15 phút lần đầu
make -C dev apps              # mọi pod phải Running
```
Chi tiết kiểm tra và vòng làm việc hằng ngày: [dev/README.md](../dev/README.md).

### Bước 7. Mở code bằng VS Code
```bash
cd ~/work/smart-hospital-platform
code .
```
Góc trái dưới của VS Code phải hiện **"WSL: Ubuntu-24.04"**. Terminal tích hợp của VS Code chính là terminal Ubuntu: chạy `mvn`, `make`, `git` ở đó.
IntelliJ IDEA: dùng bản Ultimate, **File → Open** đường dẫn `\\wsl$\Ubuntu-24.04\home\<user>\work\smart-hospital-platform`, hoặc JetBrains Gateway.

---

## Cách thủ công (nếu script lỗi)
1. PowerShell **Run as administrator**: `wsl --install -d Ubuntu-24.04`, khởi động lại máy, mở Ubuntu và đặt tài khoản.
2. Kiểm tra là WSL2: `wsl -l -v` phải thấy `VERSION 2` ở dòng Ubuntu. Nếu là 1: `wsl --set-version Ubuntu-24.04 2`.
3. Cài Docker Desktop (https://www.docker.com/products/docker-desktop/) và bật WSL integration như bước 3.
4. Cài VS Code (https://code.visualstudio.com/) và extension **WSL** (`ms-vscode-remote.remote-wsl`).
5. Tạo file `C:\Users\<bạn>\.wslconfig`:
   ```ini
   [wsl2]
   memory=10GB
   processors=6
   swap=4GB
   ```
   rồi chạy `wsl --shutdown` trong PowerShell để áp dụng.
6. Tiếp tục từ bước 4 ở trên.

## Những lỗi hay gặp
| Triệu chứng | Nguyên nhân và cách xử lý |
|---|---|
| `docker: command not found` trong Ubuntu | Chưa bật WSL integration cho Ubuntu (bước 3), hoặc Docker Desktop chưa chạy. |
| `make doctor` báo "Windows đang giữ chỗ các cổng ..." | Windows tự giữ chỗ một số dải cổng. Mở PowerShell Admin: `net stop winnat; net start winnat`, rồi thử lại. |
| `bad interpreter: /usr/bin/env bash^M` | Repo bị clone ở phía Windows (CRLF). Xóa và clone lại **trong Ubuntu** (`~/work`). |
| Build chậm, lỗi quyền file | Repo đang nằm ở `/mnt/c/...`. Clone vào `~/work` trong Ubuntu. |
| Pod `OOMKilled`, máy đơ | Tăng `memory` trong `.wslconfig` (tối đa khoảng RAM máy − 4 GB), `wsl --shutdown`, và tăng RAM trong Docker Desktop. |
| `Permission denied (publickey)` khi `git clone` | Dùng link HTTPS như hướng dẫn và đã `gh auth login`; hoặc chưa được mời vào repo. |
| Thiếu `docker buildx` | Docker Desktop đã có sẵn. Nếu tự cài Docker Engine: `sudo apt-get install docker-buildx`. |
| `wsl --install` báo lỗi virtualization | Bật Virtualization (Intel VT-x / AMD-V) trong BIOS. |

## Làm việc hằng ngày
- Luôn mở terminal **Ubuntu** (hoặc terminal trong VS Code đang ở chế độ WSL), không dùng PowerShell cho `make`/`mvn`/`git`.
- Tắt cụm khi không dùng để nhẹ máy: `make -C dev down`. Dừng hẳn WSL: `wsl --shutdown`.
- Không sửa file của repo bằng chương trình Windows qua `\\wsl$` nếu nó đổi xuống dòng thành CRLF; dùng VS Code ở chế độ WSL.
