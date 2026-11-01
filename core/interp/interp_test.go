package interp

import "testing"

func TestWhyNot(t *testing.T) {
	two := Spec{Algo: "ocl", Multi: 2}
	three := Spec{Algo: "ocl", Multi: 3}
	cases := []struct {
		name     string
		spec     Spec
		src, hz  float64
		wantFail bool
	}{
		{"24 帧补 2 倍上 60Hz", two, 23.976, 60, false},
		{"24 帧补 3 倍上 60Hz:72 帧显示不出来", three, 23.976, 60, true},
		{"24 帧补 3 倍上 75Hz", three, 23.976, 75, false},
		{"刷新率未知不判", three, 23.976, 0, false},
		{"片源未知不判", three, 0, 60, false},
		{"60 帧片源不补", two, 59.94, 240, true},
		{"30 帧补 2 倍上 60Hz 正好", two, 30, 60, false},
	}
	for _, c := range cases {
		if got := WhyNot(c.spec, c.src, c.hz) != ""; got != c.wantFail {
			t.Errorf("%s:判「不该开」=%v,期望 %v", c.name, got, c.wantFail)
		}
	}
}

// 「倍」按补了几倍叫:24→48 是补 1 倍【用户定 2026-09-17】。内部 multi=2 不变。
func TestMultiName按补几倍叫(t *testing.T) {
	if got := MultiName(2); got != "补 1 倍" {
		t.Fatalf("multi=2(24→48)应叫「补 1 倍」,实得 %q", got)
	}
	if got := FPSNote(3, 23.976); got != "24→72 帧" {
		t.Fatalf("帧数说明不对:%q", got)
	}
	if FPSNote(0, 23.976) != "" {
		t.Fatal("「关闭」那一档不该带帧数")
	}
	if FPSNote(2, 0) != "" {
		t.Fatal("片源帧率未知时不该给帧数")
	}
}
