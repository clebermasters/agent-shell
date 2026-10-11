"""Small JSONC editor for one managed MCP member, preserving surrounding text."""
import json


def tokens(text):
    result = []
    index = 0
    while index < len(text):
        start = index
        char = text[index]
        if char.isspace():
            index += 1
            continue
        if text.startswith('//', index):
            end = text.find('\n', index)
            index = len(text) if end < 0 else end
            continue
        if text.startswith('/*', index):
            end = text.find('*/', index + 2)
            if end < 0:
                raise ValueError('Unclosed JSONC comment')
            index = end + 2
            continue
        if char == '"':
            index += 1
            while index < len(text):
                if text[index] == '\\':
                    index += 2
                elif text[index] == '"':
                    index += 1
                    break
                else:
                    index += 1
            result.append(('string', start, index))
        elif char in '{}[]:,':
            result.append((char, index, index + 1))
            index += 1
        else:
            while index < len(text) and not text[index].isspace() and text[index] not in '{}[]:,/':
                index += 1
            if index == start:
                raise ValueError('Invalid JSONC token')
            result.append(('literal', start, index))
    return result


def parse(text):
    items = tokens(text)
    clean = ''.join(text[start:end] for position, (kind, start, end) in enumerate(items)
                    if not (kind == ',' and position + 1 < len(items) and items[position + 1][0] in '}]'))
    value = json.loads(clean)
    if not isinstance(value, dict):
        raise ValueError('Configuration must be an object')
    return value


def value_end(items, start):
    if items[start][0] not in ['{', '[']:
        return start
    depth = 0
    for index in range(start, len(items)):
        if items[index][0] in ['{', '[']:
            depth += 1
        elif items[index][0] in ['}', ']']:
            depth -= 1
            if depth == 0:
                return index
    raise ValueError('Unclosed JSONC value')


def member(text, items, opening, name):
    index = opening + 1
    while items[index][0] != '}':
        key = json.loads(text[items[index][1]:items[index][2]])
        assert items[index + 1][0] == ':'
        start = index + 2
        end = value_end(items, start)
        if key == name:
            return start, end
        index = end + 1
        if items[index][0] == ',':
            index += 1
    return None


def set_member(text, opening, name, value):
    items = tokens(text)
    found = member(text, items, opening, name)
    encoded = json.dumps(value, indent=2)
    if found:
        start, end = found
        return text[:items[start][1]] + encoded + text[items[end][2]:]
    closing = value_end(items, opening)
    separator = '' if closing == opening + 1 or items[closing - 1][0] == ',' else ','
    addition = separator + '\n' + json.dumps(name) + ': ' + encoded + '\n'
    return text[:items[closing][1]] + addition + text[items[closing][1]:]


def merge_mcp(text, entry):
    value = parse(text)  # Validate before editing and reject malformed input.
    if 'mcp' in value and not isinstance(value['mcp'], dict):
        raise ValueError('OpenCode mcp configuration must be an object')
    if value.get('mcp', {}).get('agentshell-ui') == entry:
        return text
    items = tokens(text)
    found = member(text, items, 0, 'mcp')
    if found:
        updated = set_member(text, found[0], 'agentshell-ui', entry)
    else:
        updated = set_member(text, 0, 'mcp', {'agentshell-ui': entry})
    expected = dict(value, mcp=dict(value.get('mcp', {}), **{'agentshell-ui': entry}))
    if parse(updated) != expected:
        raise ValueError('MCP merge did not preserve unrelated settings')
    return updated
