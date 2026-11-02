import json, sys

def emit(value):
    print(json.dumps(value), flush=True)

def result(request, value):
    emit({'jsonrpc':'2.0','id':request['id'],'result':value})

for line in sys.stdin:
    message = json.loads(line)
    method = message.get('method')
    if method == 'initialize': result(message, {'protocolVersion': 1, 'agentInfo': {'name': 'fake'}})
    elif method == 'session/new': result(message, {'sessionId': 'test-session'})
    elif method == 'session/load': result(message, {})
    elif method == 'session/prompt':
        emit({'jsonrpc':'2.0','method':'session/update','params':{'sessionId':'test-session','update':{'sessionUpdate':'agent_message_chunk','content':{'type':'text','text':'hello'}}}})
        emit({'jsonrpc':'2.0','id':777,'method':'session/request_permission','params':{'sessionId':'test-session','toolCall':{'title':'write file'},'options':[{'optionId':'yes','kind':'allow_once'},{'optionId':'no','kind':'reject_once'}]}})
        permission = json.loads(sys.stdin.readline())
        selected = permission.get('result',{}).get('outcome',{}).get('optionId')
        result(message, {'stopReason':'end_turn','selected':selected})
