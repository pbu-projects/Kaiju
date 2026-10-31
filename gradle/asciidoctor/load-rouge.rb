# frozen_string_literal: true

# Prepend vendored Rouge library to JRuby's LOAD_PATH
rouge_lib = File.expand_path('rouge/lib', __dir__)
$LOAD_PATH.unshift(rouge_lib) unless $LOAD_PATH.include?(rouge_lib)

require 'rouge'
