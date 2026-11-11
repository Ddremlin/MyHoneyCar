"""Минимальный эмулятор ELM327 по TCP (CAN 11/500, ATS0/ATH0) для проверки приложения."""
import math, socket, sys, threading, time

T0 = time.time()
def t(): return time.time() - T0

def val(pid):
    s = t()
    rpm = 800 + 1500 * (1 + math.sin(s / 5))
    spd = int(40 + 30 * math.sin(s / 10))
    v = {
        0x01: [0x82, 0x07, 0x65, 0x00],
        0x04: [int(60 + 40 * math.sin(s / 3))],
        0x05: [int(40 + 88)],
        0x0C: [int(rpm * 4) >> 8, int(rpm * 4) & 0xFF],
        0x0D: [spd],
        0x0F: [65],
        0x11: [int(40 + 30 * math.sin(s / 2))],
        0x2F: [180],
        0x42: [0x36, 0xB0],
        0x46: [55],
    }
    return v.get(pid)

SUPPORTED = {0x00: "981A8001", 0x20: "00020001", 0x40: "44000000"}
# 0100: 01,04,05,0C,0D,0F,11,20 ; 0120: 2F,40 ; 0140: 42,46

def frames(data):
    hexs = "".join("%02X" % b for b in data)
    if len(data) <= 7:
        return [hexs]
    out = ["%03X" % len(data)]
    chunks = [hexs[:12]] + [hexs[i:i + 14] for i in range(12, len(hexs), 14)]
    for i, c in enumerate(chunks):
        out.append("%X:%s" % (i % 16, c.ljust(14 if i else 12, "A")))
    return out

def answer(cmd):
    cmd = cmd.replace(" ", "").upper()
    if cmd.startswith("AT"):
        return {"ATZ": ["ELM327 v1.5"], "ATI": ["ELM327 v1.5"], "ATRV": ["%.1fV" % (12.4 + 0.3 * math.sin(t()))],
                "ATDPN": ["A6"], "ATDP": ["AUTO, ISO 15765-4 (CAN 11/500)"]}.get(cmd, ["OK"])
    if cmd.startswith("01"):
        pids = [int(cmd[i:i + 2], 16) for i in range(2, len(cmd), 2)]
        data = [0x41]
        for p in pids:
            if p in SUPPORTED:
                d = list(bytes.fromhex(SUPPORTED[p]))
            else:
                d = val(p)
            if d is None:
                continue
            data += [p] + d
        return frames(data) if len(data) > 1 else ["NO DATA"]
    if cmd == "03":
        return frames([0x43, 0x02, 0x01, 0x33, 0x04, 0x20])
    if cmd == "07":
        return ["4700"]
    if cmd == "04":
        return ["44"]
    if cmd == "0902":
        return frames([0x49, 0x02, 0x01] + list(b"XTA21099012345678"))
    return ["?"]

def client(conn, addr):
    print("client", addr, flush=True)
    buf = b""
    try:
        while True:
            d = conn.recv(256)
            if not d:
                break
            buf += d
            while b"\r" in buf:
                line, buf = buf.split(b"\r", 1)
                cmd = line.decode(errors="ignore").strip()
                if not cmd:
                    continue
                time.sleep(0.03)  # имитация шины
                resp = answer(cmd)
                print(cmd, "->", resp, flush=True)
                conn.sendall(("\r".join(resp) + "\r\r>").encode())
    except OSError:
        pass
    print("disconnected", addr, flush=True)

s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
# Порт и адрес: python3 elmsim.py [порт] [адрес]. По умолчанию только этот ПК (127.0.0.1);
# для теста с телефона по hotspot укажите адрес ПК в сети телефона или 0.0.0.0.
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 35000
HOST = sys.argv[2] if len(sys.argv) > 2 else "127.0.0.1"
s.bind((HOST, PORT)); s.listen()
print("ELM327 simulator on %s:%d" % (HOST, PORT), flush=True)
while True:
    c, a = s.accept()
    threading.Thread(target=client, args=(c, a), daemon=True).start()
