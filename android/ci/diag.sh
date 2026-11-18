# Temporary: which conversations does OpenCode's free tier refuse?
exec > /root/result-diag.txt 2>&1
cd /root
M=opencode/big-pickle
ANSWER="It is teal, yes. Tomorrow at 10 in the morning I have my driving test"
FACTS="- Now: Wednesday 18 November 2026, 03:10
- Battery: 100%, charging
- The user last wrote to you 5 minutes ago
- Language of the app: English
"
RULES="Write one short, warm message of at most three sentences that ends with ONE question for the user.
- Make it personal. Use what you know about them from your notes and from this conversation: how something they mentioned went, a plan, their health, their work, the people they talked about. Ask about the thing you planned to come back to.
- Fit the time of day (morning, afternoon, evening, night). Mention the battery only if it is below 20% and not charging.
- Do not ask something you already asked in this conversation, and do not list what you can do.
- Write in the language the user writes to you in; if you cannot tell, use the app's language.
- Your message itself is what the user gets as a notification: do not run any command now, do not use phone ask or phone notify, and change nothing on the phone.

When the user answers: reply like a friend, keep what is worth knowing with learn note, and if something deserves a later question, plan it with phone care in (see your guide)."
EXACT="[Written by the Termant app, not by the user. The user does not see this text.]

You look after the user the way a caring friend does, and this time you speak first: the user has not asked anything. You planned to come back to this: \"which colour the user likes best\".

$FACTS
$RULES"
NOHEAD="You look after the user the way a caring friend does, and this time you speak first: the user has not asked anything. You planned to come back to this: \"which colour the user likes best\".

$FACTS
$RULES"
ASUSER="Please check in on me now the way a caring friend would: write me one short, warm message that ends with one question about which colour I like best. Do not run any command."
NEUTRAL="Say hello to me in one short sentence. Do not run any command."

two() { # <name> <first message> <second message>
  echo "===== $1"
  timeout 150 opencode run --standalone --model $M "$2" 2>&1 | tail -n 6
  echo "$1-first-exit=${PIPESTATUS[0]}"
  timeout 150 opencode run --standalone --continue --model $M "$3" 2>&1 | tail -n 8
  echo "$1-second-exit=${PIPESTATUS[0]}"
}
two exact "$EXACT" "$ANSWER"
two exact-other-answer "$EXACT" "Thanks. Now tell me in one word which shell you run in."
two nohead "$NOHEAD" "$ANSWER"
two asuser "$ASUSER" "$ANSWER"
two neutral "$NEUTRAL" "$ANSWER"
two neutral-short "$NEUTRAL" "It is teal, yes."
echo "===== fresh after all of it"
timeout 120 opencode run --standalone --model $M "Reply only with the word OK" 2>&1 | tail -n 3
echo "fresh-exit=${PIPESTATUS[0]}"
echo TERMANT-TEST-END
