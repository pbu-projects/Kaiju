import sys

file_path = '/home/jimmy/git/pbu/kaiju/core/src/test/groovy/lol/pbu/kaiju/core/controller/LocationControllerSpec.groovy'

with open(file_path, 'r') as f:
    content = f.read()

# Fix 1: Make connection @Shared so it can be accessed in where block
content = content.replace('@Inject\n    Connection connection', '@Shared @Inject\n    Connection connection')

with open(file_path, 'w') as f:
    f.write(content)
