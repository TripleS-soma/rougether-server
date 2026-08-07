escape_multiline_env_value() {
  python3 -c '
import sys

value = sys.stdin.read()
value = value.replace("\r\n", "\n").replace("\r", "\n")
sys.stdout.write(value.replace("\n", r"\n"))
'
}
