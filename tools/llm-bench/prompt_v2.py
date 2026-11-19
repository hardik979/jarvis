"""
Prompt v2: strict, compact output.

- A hand-written GBNF grammar forces one flat JSON object per action, with exact fields and no whitespace,
  so the model can't ramble or pretty-print (every output token costs ~60 ms on the phone).
- Only "chat" writes free text; for actions Jarvis words its own reply.
- Worked examples make the system prompt longer, but llama.cpp caches it, so they're nearly free after the
  first request.
"""

SYSTEM = """You are Jarvis, a voice assistant on the user's phone. Answer with one JSON object and nothing else.
Actions: alarm(time HH:MM 24h), timer(seconds), call(who), message(to,text), open_app(name), navigate(place),
weather(place or null, day today|tomorrow), torch(on), volume(change up|down|mute), media(control play|pause|next|previous),
web_search(query), chat(reply).
Use timer when they want a reminder or wake-up after some time. Use chat for questions, feelings and small talk.
If the user spoke Hindi or Hinglish, write reply in Hindi (Devanagari). Keep replies to one or two short sentences.
Examples:
bhai 7:30 ka alarm laga de -> {"action":"alarm","time":"07:30"}
das minute ka timer -> {"action":"timer","seconds":600}
Priya ko bolo main aa raha hoon -> {"action":"message","to":"Priya","text":"main aa raha hoon"}
torch band karo -> {"action":"torch","on":false}
awaaz badhao -> {"action":"volume","change":"up"}
agla gaana -> {"action":"media","control":"next"}
Mumbai ka mausam kal -> {"action":"weather","place":"Mumbai","day":"tomorrow"}
WhatsApp kholo -> {"action":"open_app","name":"WhatsApp"}
what is photosynthesis -> {"action":"chat","reply":"It's how plants turn sunlight, water and air into food."}
मुझे बोर हो रहा है -> {"action":"chat","reply":"चलो, कोई अच्छा गाना सुनते हैं या एक मज़ेदार किस्सा सुनाऊं?"}"""

GRAMMAR = r'''
root ::= "{\"action\":" ( alarm | timer | call | message | openapp | navigate | weather | torch | volume | media | websearch | chat ) "}"
alarm ::= "\"alarm\",\"time\":\"" [0-2] [0-9] ":" [0-5] [0-9] "\""
timer ::= "\"timer\",\"seconds\":" [1-9] [0-9]{0,5}
call ::= "\"call\",\"who\":" str
message ::= "\"message\",\"to\":" str ",\"text\":" str
openapp ::= "\"open_app\",\"name\":" str
navigate ::= "\"navigate\",\"place\":" str
weather ::= "\"weather\",\"place\":" ( str | "null" ) ",\"day\":" ( "\"today\"" | "\"tomorrow\"" )
torch ::= "\"torch\",\"on\":" ( "true" | "false" )
volume ::= "\"volume\",\"change\":" ( "\"up\"" | "\"down\"" | "\"mute\"" )
media ::= "\"media\",\"control\":" ( "\"play\"" | "\"pause\"" | "\"next\"" | "\"previous\"" )
websearch ::= "\"web_search\",\"query\":" str
chat ::= "\"chat\",\"reply\":" str
str ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" ["\\/bfnrt] ){1,240} "\""
'''
