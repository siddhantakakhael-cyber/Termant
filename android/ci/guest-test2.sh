# Second start of the app: what was installed before must still be there.
exec > /root/result2.txt 2>&1
hello; echo "hello-exit=$?"
dpkg -s hello | head -3
echo TERMANT-TEST-END
