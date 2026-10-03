// 更新发布工具。私钥只从本地文件或 CI Secret 读取,不输出其内容。
package main

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"flag"
	"fmt"
	"os"
	"strings"

	"linplayer/core/updatesign"
)

func main() {
	verify := flag.Bool("verify", false, "仅验证清单签名")
	seedFile := flag.String("seed-file", "", "本地私钥种子文件;未指定时读 LP_UPDATE_SIGNING_SEED")
	flag.Parse()
	if flag.NArg() != 2 {
		fail(fmt.Errorf("用法: signupdate [-verify | -seed-file 文件] 发布标签 校验清单"))
	}
	tag, file := flag.Arg(0), flag.Arg(1)
	manifest, err := os.ReadFile(file)
	if err != nil {
		fail(err)
	}
	if *verify {
		sig, err := os.ReadFile(file + ".sig")
		if err != nil {
			fail(err)
		}
		if err := updatesign.Verify(updatesign.PublicKey(), tag, manifest, sig); err != nil {
			fail(err)
		}
	} else {
		encoded := os.Getenv("LP_UPDATE_SIGNING_SEED")
		if *seedFile != "" {
			b, err := os.ReadFile(*seedFile)
			if err != nil {
				fail(err)
			}
			encoded = string(b)
		}
		seed, err := base64.StdEncoding.DecodeString(strings.TrimSpace(encoded))
		if err != nil || len(seed) != ed25519.SeedSize {
			fail(fmt.Errorf("缺少有效更新签名私钥种子"))
		}
		key := ed25519.NewKeyFromSeed(seed)
		if !bytes.Equal(key.Public().(ed25519.PublicKey), updatesign.PublicKey()) {
			fail(fmt.Errorf("签名私钥与项目固定公钥不一致"))
		}
		sig := updatesign.Sign(key, tag, manifest)
		if err := updatesign.Verify(updatesign.PublicKey(), tag, manifest, sig); err != nil {
			fail(err)
		}
		if err := os.WriteFile(file+".sig", sig, 0o644); err != nil {
			fail(err)
		}
	}
	fmt.Println("更新清单签名验证通过")
}

func fail(err error) { fmt.Fprintln(os.Stderr, err); os.Exit(1) }
