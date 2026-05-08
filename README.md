# dicto
dictation toy app

## Usage Aids

While dictating, say `zeta` followed by one of these commands to insert punctuation, spacing, or apply a small edit. The trigger word defaults to `zeta`, but you can change it in the app under the Dictation engine section.

| Spoken command | Inserts |
| --- | --- |
| `zeta new line` | newline |
| `zeta newline` | newline |
| `zeta period` | `.` |
| `zeta question` | `?` |
| `zeta question mark` | `?` |
| `zeta exclamation` | `!` |
| `zeta exclamation point` | `!` |
| `zeta colon` | `:` |
| `zeta dash` | `-` |
| `zeta tab` | tab |
| `zeta delete five` | deletes the command and the previous 5 words |
| `zeta delete 5` | deletes the command and the previous 5 words |
| `zeta number one five five zeta stop` | `155` |
| `zeta number twenty one zeta stop` | `21` |
| `zeta number one hundred twenty three zeta stop` | `123` |

For delete counts, Dicto also accepts common speech-to-text homophones such as `won`, `to`, `too`, `for`, `fore`, `sex`, and `tin`.

Number spans are explicit only: Dicto does not globally normalize spoken numbers unless they are between `<trigger> number` and `<trigger> stop`.

If the words after the configured trigger do not match a command, Dicto keeps the spoken words as normal transcript text.
