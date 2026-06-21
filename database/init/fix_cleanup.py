import sys

file_path = '/home/jimmy/git/pbu/kaiju/core/src/test/groovy/lol/pbu/kaiju/core/controller/LocationControllerSpec.groovy'

with open(file_path, 'r') as f:
    content = f.read()

# Change def cleanup to public void cleanup
content = content.replace('def cleanup()', 'public void cleanup()')

with open(file_path, 'w') as f:
    f.write(content)
