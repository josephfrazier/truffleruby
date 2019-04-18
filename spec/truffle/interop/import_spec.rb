# Copyright (c) 2019 Oracle and/or its affiliates. All rights reserved. This
# code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 1.0, or
# GNU General Public License version 2, or
# GNU Lesser General Public License version 2.1.

require_relative '../../ruby/spec_helper'

guard -> { Truffle::Interop.polyglot_access? } do
  describe "Truffle::Interop.import" do

    it "imports an object" do
      object = Object.new
      Truffle::Interop.export :imports_an_object, object
      Truffle::Interop.import(:imports_an_object).should == object
    end

    it "returns nil because Truffle::Interop.lookup_symbol returns nil if not found" do
      Truffle::Interop.import(:not_registered_export_test).should == nil
    end

  end
end
